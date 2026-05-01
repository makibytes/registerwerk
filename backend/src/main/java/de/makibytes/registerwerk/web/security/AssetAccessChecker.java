package de.makibytes.registerwerk.web.security;

import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.blockchain.BlockchainTransaction;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.BlockchainTransactionRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Security helper that controls access to assets and related resources.
 */
@Component
public class AssetAccessChecker {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final BlockchainTransactionRepository transactionRepository;

    public AssetAccessChecker(
            AssetRepository assetRepository,
            AssetDeploymentRepository deploymentRepository,
            BlockchainTransactionRepository transactionRepository) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns true if the authenticated user may read the given asset.
     * REGISTRY_ADMIN and AUDIT roles have unrestricted access; others may read only
     * if their {@code entity_id} matches the asset's {@code issuerId}.
     */
    public boolean canRead(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        if (SecurityUtils.isImpersonatingAdmin(auth)) return true;
        return isOwnerOfAsset(assetId, auth);
    }

    /**
     * Returns true if the caller is the issuing company for this asset.
     * Does NOT grant access to REGISTRY_ADMIN automatically — callers should
     * compose: {@code hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(...)}.
     */
    public boolean canActAsIssuer(UUID assetId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return isOwnerOfAsset(assetId, auth);
    }

    /**
     * Returns true if the caller may read the given blockchain transaction
     * (resolved by looking up the transaction's asset and calling {@link #canRead}).
     */
    public boolean canReadTransaction(UUID txId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        Optional<BlockchainTransaction> tx = transactionRepository.findById(txId);
        if (tx.isEmpty()) return false;
        UUID assetId = tx.get().getAssetId();
        if (assetId == null) return false;
        return canRead(assetId, auth);
    }

    /**
     * Returns true if the caller may read transactions for the given deployment
     * (resolved by looking up the deployment's asset and calling {@link #canRead}).
     */
    public boolean canReadDeployment(UUID deploymentId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        Optional<AssetDeployment> dep = deploymentRepository.findById(deploymentId);
        if (dep.isEmpty()) return false;
        UUID assetId = dep.get().getAssetId();
        if (assetId == null) return false;
        return canRead(assetId, auth);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isOwnerOfAsset(UUID assetId, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;
        return assetRepository.findById(assetId)
                .map(a -> entityId.equals(a.getIssuerId()))
                .orElse(false);
    }
}
