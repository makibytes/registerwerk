package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.events.HolderBlockNotPropagatedEvent;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockFreezeResyncRequestedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>T3-15: the wallet is matched in canonical form ({@link AddressNormalizer}) — a checksum-cased
 * block used to match no {@code asset_holder} row, so nothing was frozen and nothing was logged.
 * An ACTIVE block that still resolves to no deployment while its asset has EVM deployments is
 * logged at ERROR and published as {@link HolderBlockNotPropagatedEvent}.
 *
 * <p>T3-16: the lift path uses the dedicated block-lift unfreeze variants; the manual unfreeze
 * endpoints refuse while any ACTIVE block covers the wallet.
 */
@Component
class SperrvermerkOnchainSyncListener {

    private static final Logger log = LoggerFactory.getLogger(SperrvermerkOnchainSyncListener.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private static final Set<Chain> NON_EVM_CHAINS =
            EnumSet.of(Chain.SOLANA, Chain.STARKNET, Chain.STELLAR, Chain.CANTON);

    private final AssetHolderRepository holderRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final Erc3643SuiteRepository suiteRepository;
    private final Erc3643LifecycleService erc3643LifecycleService;
    private final TokenAdminPort tokenAdminPort;
    private final HolderBlockGate holderBlockGate;
    private final ApplicationEventPublisher eventPublisher;

    SperrvermerkOnchainSyncListener(AssetHolderRepository holderRepository,
                                     AssetDeploymentRepository deploymentRepository,
                                     Erc3643SuiteRepository suiteRepository,
                                     Erc3643LifecycleService erc3643LifecycleService,
                                     TokenAdminPort tokenAdminPort,
                                     HolderBlockGate holderBlockGate,
                                     ApplicationEventPublisher eventPublisher) {
        this.holderRepository = holderRepository;
        this.deploymentRepository = deploymentRepository;
        this.suiteRepository = suiteRepository;
        this.erc3643LifecycleService = erc3643LifecycleService;
        this.tokenAdminPort = tokenAdminPort;
        this.holderBlockGate = holderBlockGate;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onHolderBlockCreated(HolderBlockCreatedEvent event) {
        propagateFreeze(event.holderBlockId(), event.payload());
    }

    /** One-shot re-propagation for blocks whose wallet V10 normalised (T3-15). */
    @ApplicationModuleListener
    void onHolderBlockFreezeResyncRequested(HolderBlockFreezeResyncRequestedEvent event) {
        propagateFreeze(event.holderBlockId(), event.payload());
    }

    private void propagateFreeze(UUID holderBlockId, Map<String, Object> payload) {
        String walletAddress = AddressNormalizer.normalize(stringDetail(payload, "walletAddress"));
        if (walletAddress == null) {
            return;
        }
        UUID assetId = uuidDetail(payload, "assetId");
        String reason = "eWpG §16 Sperrvermerk: " + stringDetail(payload, "legalBasis");
        List<AssetDeployment> deployments = deploymentsFor(walletAddress, assetId);
        if (deployments.isEmpty()) {
            alertIfNotPropagated(holderBlockId, walletAddress, assetId);
        }
        for (AssetDeployment deployment : deployments) {
            freeze(deployment, walletAddress, reason);
        }
    }

    /**
     * An asset-scoped block that matches no register row cannot be frozen on-chain. When the asset
     * has EVM deployments, that is a compliance gap (the wallet may hold units the register does
     * not attribute to it), not a no-op. A wallet-wide block with no holdings is a no-op.
     */
    private void alertIfNotPropagated(UUID holderBlockId, String walletAddress, UUID assetId) {
        if (assetId == null) {
            return;
        }
        List<UUID> evmDeployments = deploymentRepository.findByAssetId(assetId).stream()
                .filter(d -> d.getChain() == null || !NON_EVM_CHAINS.contains(d.getChain()))
                .map(AssetDeployment::getId)
                .toList();
        if (evmDeployments.isEmpty()) {
            return;
        }
        log.error("SPERRVERMERK NOT PROPAGATED: ACTIVE block={} wallet={} asset={} matches no register "
                        + "entry, so none of the asset's {} EVM deployment(s) was frozen on-chain — "
                        + "operator must verify the wallet and apply the freeze manually.",
                holderBlockId, walletAddress, assetId, evmDeployments.size());
        Map<String, Object> details = new HashMap<>();
        details.put("walletAddress", walletAddress);
        details.put("assetId", assetId.toString());
        details.put("evmDeploymentIds", evmDeployments.stream().map(UUID::toString).toList());
        eventPublisher.publishEvent(new HolderBlockNotPropagatedEvent(holderBlockId, details));
    }

    @ApplicationModuleListener
    void onHolderBlockLifted(HolderBlockLiftedEvent event) {
        String walletAddress = AddressNormalizer.normalize(stringDetail(event.payload(), "walletAddress"));
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
                erc3643LifecycleService.unfreezeAddressForBlockLift(suite.get().getId(), walletAddress);
            } else {
                tokenAdminPort.unfreezeAfterBlockLift(deployment.getId(), walletAddress);
            }
        } catch (Exception e) {
            log.error("Failed to unfreeze wallet={} on deployment={} following a lifted Sperrvermerk — "
                    + "operator must verify/apply the on-chain unfreeze manually: {}",
                    walletAddress, deployment.getId(), e.getMessage());
        }
    }

    private static String stringDetail(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    private static UUID uuidDetail(Map<String, Object> payload, String key) {
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
