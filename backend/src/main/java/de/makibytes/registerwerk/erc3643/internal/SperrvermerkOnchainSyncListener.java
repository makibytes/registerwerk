package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each token deployment's on-chain frozen flag in sync with the registry-layer §16 eWpG
 * Sperrvermerk. Previously {@code SperrvermerkService.create}/{@code lift}
 * only ever wrote {@code holder_block} rows and published audit events — nothing called the
 * token's own {@code freezeAddress}/{@code setAddressFrozen}, and {@code EwpgRepoMarket}'s
 * {@code repay}/{@code liquidate} are deliberately ungated by ecosystem permissions, relying
 * entirely on that on-chain frozen flag as the real compliance chokepoint (the backend never
 * mediates those calls directly). Without this sync, a legally blocked holder could still
 * repay/liquidate/withdraw pledged securities on-chain even though the register shows them
 * blocked.
 *
 * <p>Lives in {@code erc3643.internal} rather than {@code blockchain.internal} or
 * {@code kyc.internal} because it needs to call both {@link Erc3643LifecycleService} (same
 * module) and {@link TokenAdminPort} — {@code erc3643} already safely depends one-way on both
 * {@code blockchain.api} and {@code kyc.api}; placing this listener in either of those modules
 * would require a dependency back onto {@code erc3643}, creating a cycle.
 *
 * <p>On-chain freeze failures never roll back the Sperrvermerk itself (the DB record is the
 * legally authoritative one) — they are logged at ERROR so an operator can intervene manually,
 * since a failed freeze here is a real compliance gap, not a benign no-op.
 */
@Component
class SperrvermerkOnchainSyncListener {

    private static final Logger log = LoggerFactory.getLogger(SperrvermerkOnchainSyncListener.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetHolderRepository holderRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final Erc3643SuiteRepository suiteRepository;
    private final Erc3643LifecycleService erc3643LifecycleService;
    private final TokenAdminPort tokenAdminPort;
    private final HolderBlockGate holderBlockGate;

    SperrvermerkOnchainSyncListener(AssetHolderRepository holderRepository,
                                     AssetDeploymentRepository deploymentRepository,
                                     Erc3643SuiteRepository suiteRepository,
                                     Erc3643LifecycleService erc3643LifecycleService,
                                     TokenAdminPort tokenAdminPort,
                                     HolderBlockGate holderBlockGate) {
        this.holderRepository = holderRepository;
        this.deploymentRepository = deploymentRepository;
        this.suiteRepository = suiteRepository;
        this.erc3643LifecycleService = erc3643LifecycleService;
        this.tokenAdminPort = tokenAdminPort;
        this.holderBlockGate = holderBlockGate;
    }

    @ApplicationModuleListener
    void onHolderBlockCreated(HolderBlockCreatedEvent event) {
        String walletAddress = stringDetail(event.payload(), "walletAddress");
        if (walletAddress == null) {
            return;
        }
        UUID assetId = uuidDetail(event.payload(), "assetId");
        String reason = "eWpG §16 Sperrvermerk: " + stringDetail(event.payload(), "legalBasis");
        for (AssetDeployment deployment : deploymentsFor(walletAddress, assetId)) {
            freeze(deployment, walletAddress, reason);
        }
    }

    @ApplicationModuleListener
    void onHolderBlockLifted(HolderBlockLiftedEvent event) {
        String walletAddress = stringDetail(event.payload(), "walletAddress");
        if (walletAddress == null) {
            return;
        }
        // Another ACTIVE block may still cover this wallet (e.g. two independent court orders) —
        // unfreezing on-chain must not race ahead of the register still considering it blocked.
        if (holderBlockGate.isBlocked(null, walletAddress)) {
            log.info("Sperrvermerk lifted for wallet={} but another ACTIVE block remains — not unfreezing on-chain.",
                    walletAddress);
            return;
        }
        UUID assetId = uuidDetail(event.payload(), "assetId");
        for (AssetDeployment deployment : deploymentsFor(walletAddress, assetId)) {
            unfreeze(deployment, walletAddress);
        }
    }

    private List<AssetDeployment> deploymentsFor(String walletAddress, UUID assetId) {
        List<AssetHolder> holders = holderRepository.findByWalletAddressIn(List.of(walletAddress));
        return holders.stream()
                .map(AssetHolder::getAssetId)
                .filter(id -> assetId == null || assetId.equals(id))
                .distinct()
                .flatMap(id -> deploymentRepository.findByAssetId(id).stream())
                .toList();
    }

    private void freeze(AssetDeployment deployment, String walletAddress, String reason) {
        try {
            Optional<Erc3643Suite> suite = suiteRepository.findByAssetDeploymentId(deployment.getId());
            if (suite.isPresent()) {
                erc3643LifecycleService.freezeAddress(suite.get().getId(), walletAddress, SYSTEM_ACTOR, "SYSTEM");
            } else {
                tokenAdminPort.freezeAddress(deployment.getId(), walletAddress, reason, reason, SYSTEM_ACTOR, "SYSTEM");
            }
        } catch (Exception e) {
            log.error("Failed to freeze wallet={} on deployment={} following a new Sperrvermerk — "
                    + "operator must verify/apply the on-chain freeze manually: {}",
                    walletAddress, deployment.getId(), e.getMessage());
        }
    }

    private void unfreeze(AssetDeployment deployment, String walletAddress) {
        try {
            Optional<Erc3643Suite> suite = suiteRepository.findByAssetDeploymentId(deployment.getId());
            if (suite.isPresent()) {
                erc3643LifecycleService.unfreezeAddress(suite.get().getId(), walletAddress, SYSTEM_ACTOR, "SYSTEM");
            } else {
                tokenAdminPort.unfreezeAddress(deployment.getId(), walletAddress, SYSTEM_ACTOR, "SYSTEM");
            }
        } catch (Exception e) {
            log.error("Failed to unfreeze wallet={} on deployment={} following a lifted Sperrvermerk — "
                    + "operator must verify/apply the on-chain unfreeze manually: {}",
                    walletAddress, deployment.getId(), e.getMessage());
        }
    }

    private static String stringDetail(java.util.Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    private static UUID uuidDetail(java.util.Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
