package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondRedeemedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reacts to {@link CantonBondRedeemedEvent} by zeroing the register's copy of each holder's
 * position. Previously {@code CantonBondService.redeem} correctly
 * exercised {@code Redeem} on the DAML ledger and published this event, but nothing consumed
 * it — {@code AssetHolder.nominalAmount} for a "redeemed" Canton bond was never touched by any
 * code path, so the register kept showing the bond as a live, full-value holding indefinitely
 * even though the on-ledger contract had been retired.
 *
 * <p>Unlike {@code AssetRedemptionListener} (ERC-20/721/1155 — dispatches an on-chain burn
 * <em>then</em> relies on the indexer to reconcile the balance), there is no EVM-style indexer
 * for Canton: the ledger side is already retired by the time this event fires, so this listener
 * only needs to update the off-chain register to match, not dispatch a further chain call.
 */
@Component
class CantonBondRedemptionListener {

    private static final Logger log = LoggerFactory.getLogger(CantonBondRedemptionListener.class);

    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetHolderRepository holderRepository;
    private final ApplicationEventPublisher events;

    CantonBondRedemptionListener(AssetDeploymentRepository deploymentRepository,
                                 AssetHolderRepository holderRepository,
                                 ApplicationEventPublisher events) {
        this.deploymentRepository = deploymentRepository;
        this.holderRepository = holderRepository;
        this.events = events;
    }

    @ApplicationModuleListener
    void onCantonBondRedeemed(CantonBondRedeemedEvent event) {
        Optional<AssetDeployment> deployment = deploymentRepository.findById(event.deploymentId());
        if (deployment.isEmpty()) {
            log.warn("AssetDeployment not found for Canton bond redemption: deploymentId={}", event.deploymentId());
            return;
        }
        UUID assetId = deployment.get().getAssetId();

        List<AssetHolder> holders = holderRepository.findActiveByAssetId(assetId);
        UUID actorId = event.actorId() != null ? event.actorId() : SYSTEM_ACTOR;
        String actorRole = event.actorId() != null ? "REGISTRY_ADMIN" : "SYSTEM";

        int zeroed = 0;
        for (AssetHolder holder : holders) {
            BigDecimal nominal = holder.getNominalAmount();
            if (nominal == null || nominal.signum() <= 0) {
                continue;
            }
            holder.setNominalAmount(BigDecimal.ZERO);
            holderRepository.save(holder);
            events.publishEvent(new HolderRegisterChangedEvent(holder.getId(), actorId, actorRole));
            zeroed++;
        }
        log.info("Canton bond redemption: assetId={} deploymentId={} zeroed {} of {} holder position(s).",
                assetId, event.deploymentId(), zeroed, holders.size());
    }
}
