package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.kyc.events.KycExpiringEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.events.KycChainPropagationEvent;
import de.makibytes.registerwerk.shared.AfterCommit;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes a KYC lapse onto every chain where the legal entity has an org or an ONCHAINID (T-REX
 * claims and the ecosystem gates otherwise keep admitting it indefinitely):
 * <ol>
 *   <li>{@code OrgRegistry.suspendOrg} through {@link OrgRegistrationService#suspend} — the
 *       existing fail-closed intent, broadcast after commit and confirmed by
 *       {@link OrgEcosystemTxPoller}. This covers every {@code PermissionOracle}-gated dApp and
 *       the paymaster;</li>
 *   <li>{@code ONCHAINID.removeClaim} plus issuer-level {@code ClaimIssuer.revokeClaimBySignature}
 *       for the KYC (1) / AML (2) claims, via the {@link Erc3643Api} port.</li>
 * </ol>
 * Triggers: {@link KycExpiringEvent} with {@code reason=EXPIRED} and {@link KycRejectedEvent}.
 * The response to an unreviewed sanctions hit ({@code ScreeningHitDetectedEvent}) is a parked
 * product decision and is deliberately not consumed here.
 *
 * <p>Durability: the event listener only records a {@code kyc_chain_propagation} row per chain
 * (the Modulith event-publication registry re-delivers the event if that fails); the rows are
 * then driven to completion right after commit and by {@link #retryOpen()} every minute. Every
 * step is idempotent, so a pass may repeat freely. A row is COMPLETED only once the suspension
 * and every revocation are confirmed on chain. Failures are logged at ERROR, audited, and counted
 * by the {@code registerwerk_kyc_chain_propagation_failed} gauge for alerting.
 *
 * <p>Nothing here is ever reversed automatically: re-approval goes through the existing 4-eyes
 * KYC approval and org reinstatement, and fresh claims are issued explicitly. A re-approval
 * before a row completes marks it SUPERSEDED.
 */
@Component
class KycChainPropagationListener {

    private static final Logger log = LoggerFactory.getLogger(KycChainPropagationListener.class);

    static final String TRIGGER_EXPIRED = "KYC_EXPIRED";
    static final String TRIGGER_REJECTED = "KYC_REJECTED";

    /** Org step outcomes; only SUSPENDED and NOT_REGISTERED let a row complete. */
    static final String ORG_SUSPENDED = "SUSPENDED";
    static final String ORG_NOT_REGISTERED = "NOT_REGISTERED";
    static final String ORG_SUSPEND_SUBMITTED = "SUSPEND_SUBMITTED";
    static final String ORG_SUSPEND_PENDING = "SUSPEND_PENDING";
    static final String ORG_WAITING = "WAITING";

    private final KycChainPropagationRepository propagationRepository;
    private final OrgRegistrationRepository registrationRepository;
    private final OrgRegistrationService registrationService;
    private final LegalEntityRepository legalEntityRepository;
    private final Erc3643Api erc3643Api;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate stepTransactions;

    KycChainPropagationListener(
            KycChainPropagationRepository propagationRepository,
            OrgRegistrationRepository registrationRepository,
            OrgRegistrationService registrationService,
            LegalEntityRepository legalEntityRepository,
            Erc3643Api erc3643Api,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.propagationRepository = propagationRepository;
        this.registrationRepository = registrationRepository;
        this.registrationService = registrationService;
        this.legalEntityRepository = legalEntityRepository;
        this.erc3643Api = erc3643Api;
        this.eventPublisher = eventPublisher;
        this.stepTransactions = new TransactionTemplate(transactionManager);
        this.stepTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Gauge.builder("registerwerk_kyc_chain_propagation_failed", propagationRepository,
                        repo -> (double) repo.countByStatus(KycChainPropagation.Status.FAILED))
                .description("KYC lapses (expiry/rejection) whose on-chain suspension/claim revocation "
                        + "is failing; the entity may still pass on-chain gates")
                .register(meterRegistry);
    }

    @ApplicationModuleListener
    void on(KycExpiringEvent event) {
        if (event.details() != null && "EXPIRED".equals(event.details().get("reason"))) {
            record(event.entityId(), TRIGGER_EXPIRED);
        }
    }

    @ApplicationModuleListener
    void on(KycRejectedEvent event) {
        record(event.entityId(), TRIGGER_REJECTED);
    }

    /** Records (or resets) one PENDING row per chain and drives them once this tx commits. */
    void record(UUID legalEntityId, String trigger) {
        Set<UUID> chains = new LinkedHashSet<>();
        registrationRepository.findByLegalEntityId(legalEntityId)
                .forEach(r -> chains.add(r.getChainConfigId()));
        chains.addAll(erc3643Api.identityChainIds(legalEntityId));
        if (chains.isEmpty()) {
            log.info("KYC lapse ({}) for entity={}: no org or ONCHAINID on any chain, nothing to propagate",
                    trigger, legalEntityId);
            return;
        }
        List<UUID> rowIds = new ArrayList<>();
        for (UUID chainConfigId : chains) {
            KycChainPropagation row = propagationRepository
                    .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
                    .orElseGet(KycChainPropagation::new);
            row.setLegalEntityId(legalEntityId);
            row.setChainConfigId(chainConfigId);
            row.setTriggerReason(trigger);
            row.setStatus(KycChainPropagation.Status.PENDING);
            row.setOrgAction(null);
            row.setUnresolvedClaims(0);
            row.setAttempts(0);
            row.setLastError(null);
            row.setCompletedAt(null);
            row.setUpdatedAt(Instant.now());
            rowIds.add(propagationRepository.save(row).getId());
        }
        log.warn("KYC lapse ({}) for entity={}: propagating to {} chain(s)", trigger, legalEntityId, rowIds.size());
        AfterCommit.run(() -> rowIds.forEach(this::driveSafely));
    }

    @SchedulerLock(name = "kycChainPropagation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retryOpen() {
        for (KycChainPropagation row : propagationRepository.findByStatusIn(
                List.of(KycChainPropagation.Status.PENDING, KycChainPropagation.Status.FAILED))) {
            driveSafely(row.getId());
        }
    }

    /** One pass over one row; never throws. */
    void driveSafely(UUID rowId) {
        try {
            drive(rowId);
        } catch (RuntimeException e) {
            log.error("KYC chain propagation row={} pass crashed: {}", rowId, e.getMessage(), e);
        }
    }

    private void drive(UUID rowId) {
        KycChainPropagation row = propagationRepository.findById(rowId).orElse(null);
        if (row == null || row.getStatus() == KycChainPropagation.Status.COMPLETED
                || row.getStatus() == KycChainPropagation.Status.SUPERSEDED) {
            return;
        }
        UUID entityId = row.getLegalEntityId();
        UUID chainConfigId = row.getChainConfigId();
        KycStatus kycStatus = legalEntityRepository.findById(entityId)
                .map(e -> e.getKycStatus()).orElse(null);
        if (kycStatus != KycStatus.EXPIRED && kycStatus != KycStatus.REJECTED) {
            // Re-approved (through 4-eyes) before this lapse finished propagating: stop pushing it.
            // Whatever already reached the chain stays until explicit reinstatement / re-issuance.
            finish(row, KycChainPropagation.Status.SUPERSEDED, null, "kycStatus=" + kycStatus);
            return;
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("trigger", row.getTriggerReason());
        audit.put("chainConfigId", chainConfigId.toString());
        String reason = "KYC lapse (" + row.getTriggerReason() + ") — automatic on-chain propagation";

        List<String> errors = new ArrayList<>();
        String orgAction;
        try {
            // Own transaction: a failing claim step below must never roll back the suspend intent.
            orgAction = stepTransactions.execute(tx -> suspendOrg(entityId, chainConfigId, reason));
        } catch (RuntimeException e) {
            orgAction = row.getOrgAction();
            errors.add("org suspension: " + e.getMessage());
            log.error("KYC chain propagation: suspending org of entity={} on chain={} failed",
                    entityId, chainConfigId, e);
        }
        Integer unresolved;
        try {
            unresolved = stepTransactions.execute(tx -> erc3643Api.revokeComplianceClaims(
                    entityId, chainConfigId, null, "SYSTEM", audit));
        } catch (RuntimeException e) {
            unresolved = null;
            errors.add("claim revocation: " + e.getMessage());
            log.error("KYC chain propagation: revoking KYC/AML claims of entity={} on chain={} failed",
                    entityId, chainConfigId, e);
        }

        row.setAttempts(row.getAttempts() + 1);
        row.setOrgAction(orgAction);
        if (unresolved != null) {
            row.setUnresolvedClaims(unresolved);
        }
        if (!errors.isEmpty()) {
            finish(row, KycChainPropagation.Status.FAILED, String.join("; ", errors), null);
        } else if ((ORG_SUSPENDED.equals(orgAction) || ORG_NOT_REGISTERED.equals(orgAction))
                && unresolved != null && unresolved == 0) {
            finish(row, KycChainPropagation.Status.COMPLETED, null, null);
        } else {
            // Waiting for on-chain confirmation (or for an in-flight registration/reinstatement).
            row.setStatus(KycChainPropagation.Status.PENDING);
            row.setLastError(null);
            row.setUpdatedAt(Instant.now());
            stepTransactions.executeWithoutResult(tx -> propagationRepository.save(row));
        }
    }

    /**
     * Idempotent org step; returns the resulting org state. Suspension is submitted from ACTIVE
     * or SUSPEND_FAILED only; in-flight registration/reinstatement is waited out and retried.
     */
    private String suspendOrg(UUID entityId, UUID chainConfigId, String reason) {
        OrgRegistration registration = registrationRepository
                .findByLegalEntityIdAndChainConfigId(entityId, chainConfigId).orElse(null);
        if (registration == null) {
            return ORG_NOT_REGISTERED;
        }
        return switch (registration.getStatus()) {
            case ACTIVE, SUSPEND_FAILED -> {
                registrationService.suspend(registration.getId(), reason, null, "SYSTEM");
                yield ORG_SUSPEND_SUBMITTED;
            }
            case SUSPEND_PENDING -> ORG_SUSPEND_PENDING;
            // REINSTATE_FAILED: the reinstate reverted, so the org is still suspended on chain.
            case SUSPENDED, REINSTATE_FAILED -> ORG_SUSPENDED;
            case FAILED -> ORG_NOT_REGISTERED;
            case PENDING, REINSTATE_PENDING -> ORG_WAITING;
        };
    }

    private void finish(KycChainPropagation row, KycChainPropagation.Status status, String error, String note) {
        boolean changed = row.getStatus() != status;
        row.setStatus(status);
        row.setLastError(error);
        row.setUpdatedAt(Instant.now());
        if (status == KycChainPropagation.Status.COMPLETED) {
            row.setCompletedAt(Instant.now());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status.name());
        details.put("trigger", row.getTriggerReason());
        details.put("chainConfigId", row.getChainConfigId().toString());
        details.put("attempts", row.getAttempts());
        if (row.getOrgAction() != null) details.put("orgAction", row.getOrgAction());
        details.put("unresolvedClaims", row.getUnresolvedClaims());
        if (error != null) details.put("error", error);
        if (note != null) details.put("note", note);
        stepTransactions.executeWithoutResult(tx -> {
            propagationRepository.save(row);
            // Audit state changes only, not every repeated FAILED retry.
            if (changed) {
                eventPublisher.publishEvent(new KycChainPropagationEvent(row.getLegalEntityId(), details));
            }
        });
        if (status == KycChainPropagation.Status.FAILED) {
            log.error("KYC chain propagation FAILED for entity={} chain={} (attempt {}): {} — the entity may "
                    + "still pass on-chain gates; retrying every minute", row.getLegalEntityId(),
                    row.getChainConfigId(), row.getAttempts(), error);
        }
    }
}
