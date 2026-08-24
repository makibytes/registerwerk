package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrder;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrderRepository;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Security helper that controls access to assets and related resources. */
@Component
public class AssetAccessChecker {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetHolderRepository holderRepository;
    private final BlockchainApi blockchainApi;
    private final AssetTokenAdminGrantRepository tokenAdminGrantRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;

    public AssetAccessChecker(
            AssetRepository assetRepository,
            AssetDeploymentRepository deploymentRepository,
            AssetHolderRepository holderRepository,
            BlockchainApi blockchainApi,
            AssetTokenAdminGrantRepository tokenAdminGrantRepository,
            SubscriptionOrderRepository subscriptionOrderRepository) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.holderRepository = holderRepository;
        this.blockchainApi = blockchainApi;
        this.tokenAdminGrantRepository = tokenAdminGrantRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
    }

    public boolean canRead(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        if (SecurityUtils.isImpersonatingAdmin(auth)) return true;
        return isOwnerOfAsset(assetId, auth);
    }

    public boolean canActAsIssuer(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return isOwnerOfAsset(assetId, auth);
    }

    /**
     * True if the caller's entity holds an ACTIVE, non-expired ASSET_TOKEN_ADMIN grant that
     * is either scoped to this asset or entity-wide. Gates forcedTransfer/forcedApprove/
     * forceBurn — deliberately NOT satisfied by {@link #canActAsIssuer} or {@link #canRead}:
     * owning or holding an asset no longer implies the authority to force-move its tokens,
     * that authority must be explicitly granted (see {@code AssetTokenAdminGrantController}).
     * REGISTRY_ADMIN's bypass is handled by the "hasRole('REGISTRY_ADMIN') or ..." SpEL at
     * the call site, not here — this method only resolves the delegated-grant path.
     */
    public boolean canForceAdmin(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;
        return tokenAdminGrantRepository.existsActiveForEntityAndAsset(entityId, assetId, Instant.now());
    }

    public boolean canReadTransaction(UUID txId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        Optional<BlockchainTransactionView> tx = blockchainApi.findTransaction(txId);
        if (tx.isEmpty()) return false;
        UUID assetId = tx.get().assetId();
        if (assetId == null) return false;
        return canRead(assetId, auth);
    }

    public boolean canActAsIssuerForOrder(UUID orderId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        Optional<SubscriptionOrder> order = subscriptionOrderRepository.findById(orderId);
        if (order.isEmpty()) return false;
        return canActAsIssuer(order.get().getAssetId(), auth);
    }

    public boolean canReadDeployment(UUID deploymentId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        Optional<AssetDeployment> dep = deploymentRepository.findById(deploymentId);
        if (dep.isEmpty()) return false;
        UUID assetId = dep.get().getAssetId();
        if (assetId == null) return false;
        return canRead(assetId, auth);
    }

    /**
     * True if the caller's entity holds a position (any {@code AssetHolder} row, confidential or
     * not) in this asset — deliberately narrower than {@link #canRead} (issuer-only) and used
     * ONLY where a holder genuinely needs asset-level metadata about their OWN holding (currently:
     * {@code ConfidentialContextController} — a holder needs the confidential token's contract
     * address/chain to read+decrypt their own encrypted balance client-side). Not wired into
     * {@link #canRead} itself, to avoid widening every existing {@code canRead}-gated endpoint's
     * access model as a side effect.
     */
    public boolean isHolderOfAsset(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;
        // Active-only: a removed holder no longer holds the position and loses this access grant.
        return holderRepository.existsActiveByAssetIdAndInvestorId(assetId, entityId);
    }

    private boolean isOwnerOfAsset(UUID assetId, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;
        return assetRepository.findById(assetId)
                .map(a -> entityId.equals(a.getIssuerId()))
                .orElse(false);
    }
}
