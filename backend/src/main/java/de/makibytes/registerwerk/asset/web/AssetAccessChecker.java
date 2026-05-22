package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Security helper that controls access to assets and related resources. */
@Component
public class AssetAccessChecker {

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final BlockchainApi blockchainApi;

    public AssetAccessChecker(
            AssetRepository assetRepository,
            AssetDeploymentRepository deploymentRepository,
            BlockchainApi blockchainApi) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.blockchainApi = blockchainApi;
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

    public boolean canReadTransaction(UUID txId, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        if (SecurityUtils.isAdminOrAudit(auth)) return true;
        Optional<BlockchainTransactionView> tx = blockchainApi.findTransaction(txId);
        if (tx.isEmpty()) return false;
        UUID assetId = tx.get().assetId();
        if (assetId == null) return false;
        return canRead(assetId, auth);
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

    private boolean isOwnerOfAsset(UUID assetId, Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) return false;
        return assetRepository.findById(assetId)
                .map(a -> entityId.equals(a.getIssuerId()))
                .orElse(false);
    }
}
