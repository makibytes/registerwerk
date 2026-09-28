package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetDeploymentRepository extends JpaRepository<AssetDeployment, UUID> {

    List<AssetDeployment> findByAssetId(UUID assetId);

    /**
     * T3-19: whether a live deployment attempt exists for (asset, chain config). An asset may have
     * several rows per chain (FAILED attempts), so there is deliberately no single-result lookup by
     * that pair; deploy steps look rows up by id.
     */
    boolean existsByAssetIdAndChainConfigIdAndDeploymentStatusIn(
            UUID assetId, UUID chainConfigId, java.util.Collection<AssetDeployment.DeploymentStatus> statuses);

    Optional<AssetDeployment> findByIdAndAssetId(UUID id, UUID assetId);

    /** Serializes irreversible admin submissions whose pending projection may not exist yet. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deployment from AssetDeployment deployment where deployment.id = :id")
    Optional<AssetDeployment> findByIdForUpdate(@Param("id") UUID id);

    List<AssetDeployment> findByChainAndNetwork(Chain chain, Network network);

    Optional<AssetDeployment> findByChainAndNetworkAndContractAddress(
        Chain chain, Network network, String contractAddress);

    Optional<AssetDeployment> findFirstByContractAddressIgnoreCase(String contractAddress);

    /** Resolves an address only inside its concrete network; CREATE2 and test deployments can
     *  legitimately reuse the same address on several EVM chains. */
    Optional<AssetDeployment> findFirstByChainConfigIdAndContractAddressIgnoreCase(
            UUID chainConfigId, String contractAddress);

    /** Deployments awaiting confirmation-depth / L1-acceptance re-verification. */
    List<AssetDeployment> findByDeploymentStatusAndDeployedByTxIsNotNull(AssetDeployment.DeploymentStatus status);
}
