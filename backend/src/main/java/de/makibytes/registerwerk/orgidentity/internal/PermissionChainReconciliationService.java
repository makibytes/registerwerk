package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.events.OrgChainDriftEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;

import java.util.List;
import java.util.Map;

/**
 * Detects drift between the DB mirror and onchain PermissionRegistry state.
 *
 * <p>{@code PermissionRegistry.grantToRole}/{@code revokeFromRole}/{@code setRoleRestricted} are
 * callable directly onchain by any org admin (the same {@code _checkOrgAuthority} — ERC-734
 * MANAGEMENT key — pattern as {@link OrgChainReconciliationService}'s {@code OrgRegistry}), fully
 * bypassing {@code PermissionAdminService}. Unlike org membership, this had zero drift detection
 * at all before this class: an org admin could grant/revoke a role-tier permission delegation, or
 * flip the role-restriction flag, with no DB row, no audit event, ever. Mirrors {@code
 * OrgChainReconciliationService}'s own pattern exactly: detection only, never auto-corrects.
 */
@Component
class PermissionChainReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PermissionChainReconciliationService.class);
    private static final int BATCH_LIMIT = 200;

    private final PermissionGrantRepository grantRepository;
    private final PermissionDefinitionRepository definitionRepository;
    private final OrgRegistrationRepository registrationRepository;
    private final EcosystemTxGateway txGateway;
    private final ApplicationEventPublisher eventPublisher;

    // Same rationale as OrgChainReconciliationService's driftCount: no dedicated table, so a
    // Reset and recount per sweep so the gauge represents current drift.
    private final SweepDriftCounter driftCount;

    PermissionChainReconciliationService(
            PermissionGrantRepository grantRepository,
            PermissionDefinitionRepository definitionRepository,
            OrgRegistrationRepository registrationRepository,
            EcosystemTxGateway txGateway,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.grantRepository = grantRepository;
        this.definitionRepository = definitionRepository;
        this.registrationRepository = registrationRepository;
        this.txGateway = txGateway;
        this.eventPublisher = eventPublisher;

        this.driftCount = new SweepDriftCounter(meterRegistry,
                "registerwerk_permission_chain_drift_open_total",
                "Count of permission-grant drift (incl. role-restriction flips) found in the most recent reconciliation sweep");
    }

    // initialDelay offset from OrgChainReconciliationService's 120_000 so the two jobs don't
    // hammer the same chain RPC endpoints in the same instant every cycle.
    @SchedulerLock(name = "permissionChainReconciliation", lockAtMostFor = "PT4M")
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void reconcile() {
        driftCount.reset();
        List<PermissionGrant> activeGrants = grantRepository.findByStatus(PermissionGrantStatus.ACTIVE);
        int checked = 0;
        for (PermissionGrant grant : activeGrants) {
            if (checked++ >= BATCH_LIMIT) break;
            try {
                reconcileGrant(grant);
            } catch (IllegalStateException e) {
                log.debug("Skipping reconciliation for grant={} (contract not configured): {}",
                        grant.getId(), e.getMessage());
            } catch (Exception e) {
                log.debug("Reconciliation read failed for grant={}: {}", grant.getId(), e.getMessage());
            }
        }
    }

    private void reconcileGrant(PermissionGrant grant) {
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId()).orElse(null);
        if (org == null) return;
        PermissionDefinition definition = definitionRepository.findById(grant.getPermissionDefinitionId()).orElse(null);
        if (definition == null) return;
        ChainConfig chain = txGateway.requireChain(org.getChainConfigId());

        if (grant.getGrantType() == PermissionGrantType.ORG) {
            reconcileOrgGrant(grant, org, definition, chain);
        } else {
            reconcileRoleGrant(grant, org, definition, chain);
        }
    }

    private void reconcileOrgGrant(PermissionGrant grant, OrgRegistration org, PermissionDefinition definition,
                                   ChainConfig chain) {
        List<Type> granted = txGateway.callPermissionRegistry(chain,
                EcosystemAbi.orgGranted(org.getOrgAddress(), definition.getCode()));
        if (granted.isEmpty()) return;
        boolean onchainGranted = ((Bool) granted.get(0)).getValue();
        if (!onchainGranted) {
            driftCount.increment();
            log.warn("Drift: grant={} is ACTIVE (ORG) in DB but not granted onchain (org={}, permission={})",
                    grant.getId(), org.getOrgAddress(), definition.getCode());
            eventPublisher.publishEvent(new OrgChainDriftEvent(grant.getId(), "PermissionGrant", Map.of(
                    "permission", definition.getCode(),
                    "orgAddress", org.getOrgAddress(),
                    "grantType", "ORG",
                    "onchainGranted", false
            )));
        }

        // The role-restriction flag is only meaningful on ORG grants — flipping it broader
        // (restricted -> unrestricted) directly onchain is the single most dangerous drift of
        // the three this job watches for, since it silently widens who can act on this
        // permission without any operator/backend involvement at all.
        List<Type> restricted = txGateway.callPermissionRegistry(chain,
                EcosystemAbi.isRoleRestricted(org.getOrgAddress(), definition.getCode()));
        if (restricted.isEmpty()) return;
        boolean onchainRestricted = ((Bool) restricted.get(0)).getValue();
        if (onchainRestricted != grant.isRoleRestricted()) {
            driftCount.increment();
            log.warn("Drift: grant={} roleRestricted={} in DB but {} onchain (org={}, permission={})",
                    grant.getId(), grant.isRoleRestricted(), onchainRestricted, org.getOrgAddress(), definition.getCode());
            eventPublisher.publishEvent(new OrgChainDriftEvent(grant.getId(), "PermissionGrant", Map.of(
                    "permission", definition.getCode(),
                    "orgAddress", org.getOrgAddress(),
                    "dbRoleRestricted", grant.isRoleRestricted(),
                    "onchainRoleRestricted", onchainRestricted
            )));
        }
    }

    private void reconcileRoleGrant(PermissionGrant grant, OrgRegistration org, PermissionDefinition definition,
                                    ChainConfig chain) {
        List<Type> result = txGateway.callPermissionRegistry(chain,
                EcosystemAbi.roleGranted(org.getOrgAddress(), grant.getRoleCode(), definition.getCode()));
        if (result.isEmpty()) return;
        boolean onchainGranted = ((Bool) result.get(0)).getValue();
        if (!onchainGranted) {
            driftCount.increment();
            log.warn("Drift: grant={} is ACTIVE (ROLE={}) in DB but not granted onchain (org={}, permission={})",
                    grant.getId(), grant.getRoleCode(), org.getOrgAddress(), definition.getCode());
            eventPublisher.publishEvent(new OrgChainDriftEvent(grant.getId(), "PermissionGrant", Map.of(
                    "permission", definition.getCode(),
                    "orgAddress", org.getOrgAddress(),
                    "grantType", "ROLE",
                    "roleCode", grant.getRoleCode(),
                    "onchainGranted", false
            )));
        }
    }
}
