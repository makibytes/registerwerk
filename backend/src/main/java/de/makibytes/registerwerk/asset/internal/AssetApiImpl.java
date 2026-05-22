package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.AssetApi;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetHolder;
import de.makibytes.registerwerk.asset.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class AssetApiImpl implements AssetApi {

    private final AssetRepository assetRepo;
    private final AssetDeploymentRepository deploymentRepo;
    private final AssetHolderRepository holderRepo;

    AssetApiImpl(AssetRepository assetRepo, AssetDeploymentRepository deploymentRepo, AssetHolderRepository holderRepo) {
        this.assetRepo = assetRepo;
        this.deploymentRepo = deploymentRepo;
        this.holderRepo = holderRepo;
    }

    @Override
    public Optional<Asset> findAsset(UUID id) { return assetRepo.findById(id); }

    @Override
    public Optional<AssetDeployment> findDeployment(UUID id) { return deploymentRepo.findById(id); }

    @Override
    public List<AssetDeployment> findDeploymentsByAsset(UUID assetId) { return deploymentRepo.findByAssetId(assetId); }

    @Override
    public Optional<AssetHolder> findHolder(UUID id) { return holderRepo.findById(id); }

    @Override
    public List<AssetHolder> findHoldersByInvestor(UUID investorId) { return holderRepo.findByInvestorId(investorId); }
}
