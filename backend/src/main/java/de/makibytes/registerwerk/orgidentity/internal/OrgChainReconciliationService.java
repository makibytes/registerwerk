package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.events.OrgChainDriftEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;

import java.util.List;
import java.util.Map;

/**
 * Detects drift between the DB mirror and onchain OrgRegistry state. Drift is expected
 * when org admins self-administer directly onchain (ONCHAINID MANAGEMENT keys) — until
 * the indexer covers ecosystem events, this job keeps the mirror honest by flagging
 * mismatches (audit event + warning); it never auto-corrects regulatory state.
 */
@Component
class OrgChainReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrgChainReconciliationService.class);
    private static final int BATCH_LIMIT = 200;

    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final EcosystemTxGateway txGateway;
    private final ApplicationEventPublisher eventPublisher;

    // Only a fire-and-forget OrgChainDriftEvent per detection, re-published every cycle for the
    // same unresolved drift — reset-then-recount each full sweep gives an accurate "currently
    // open drift" snapshot without needing new persisted state (repo-wide alerting-gap follow-up).
    private final SweepDriftCounter driftCount;

    OrgChainReconciliationService(
            OrgRegistrationRepository registrationRepository,
            OrgMemberWalletRepository walletRepository,
            EcosystemTxGateway txGateway,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.txGateway = txGateway;
        this.eventPublisher = eventPublisher;

        this.driftCount = new SweepDriftCounter(meterRegistry,
                "registerwerk_org_chain_drift_open_total",
                "Count of org/member-wallet drift found in the most recent reconciliation sweep");
    }

    @SchedulerLock(name = "orgChainReconciliation", lockAtMostFor = "PT4M")
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void reconcile() {
        driftCount.reset();
        List<OrgRegistration> activeOrgs = registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE);
        int checked = 0;
        for (OrgRegistration org : activeOrgs) {
            if (checked++ >= BATCH_LIMIT) break;
            try {
                reconcileOrg(org);
            } catch (IllegalStateException e) {
                log.debug("Skipping reconciliation for org={} (contract not configured): {}",
                        org.getId(), e.getMessage());
            } catch (Exception e) {
                log.debug("Reconciliation read failed for org={}: {}", org.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOrg(OrgRegistration org) {
        ChainConfig chain = txGateway.requireChain(org.getChainConfigId());

        List<Type> result = txGateway.callOrgRegistry(chain, EcosystemAbi.isOrgActive(org.getOrgAddress()));
        if (result.isEmpty()) return;
        boolean onchainActive = ((Bool) result.get(0)).getValue();
        if (!onchainActive) {
            driftCount.increment();
            log.warn("Drift: org registration={} is ACTIVE in DB but not active onchain (org={})",
                    org.getId(), org.getOrgAddress());
            eventPublisher.publishEvent(new OrgChainDriftEvent(org.getId(), "OrgRegistration", Map.of(
                    "orgAddress", org.getOrgAddress(),
                    "dbStatus", org.getStatus().name(),
                    "onchainActive", false
            )));
            return;
        }

        for (OrgMemberWallet wallet : walletRepository
                .findByOrgRegistrationIdOrderByCreatedAtDesc(org.getId())) {
            if (wallet.getStatus() != MemberWalletStatus.ACTIVE) continue;
            List<Type> orgOf = txGateway.callOrgRegistry(chain, EcosystemAbi.orgOf(wallet.getWalletAddress()));
            if (orgOf.isEmpty()) continue;
            String boundOrg = ((Address) orgOf.get(0)).getValue();
            if (!boundOrg.equalsIgnoreCase(org.getOrgAddress())) {
                driftCount.increment();
                log.warn("Drift: wallet={} is ACTIVE in DB but bound to {} onchain",
                        wallet.getWalletAddress(), boundOrg);
                eventPublisher.publishEvent(new OrgChainDriftEvent(wallet.getId(), "OrgMemberWallet", Map.of(
                        "wallet", wallet.getWalletAddress(),
                        "expectedOrg", org.getOrgAddress(),
                        "onchainOrg", boundOrg
                )));
            }
        }
    }
}
