package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetDeploymentRepository extends JpaRepository<AssetDeployment, UUID> {

    List<AssetDeployment> findByAssetId(UUID assetId);

    Optional<AssetDeployment> findByIdAndAssetId(UUID id, UUID assetId);

    List<AssetDeployment> findByChainAndNetwork(Chain chain, Network network);

    Optional<AssetDeployment> findByChainAndNetworkAndContractAddress(
        Chain chain, Network network, String contractAddress);

    Optional<AssetDeployment> findFirstByContractAddressIgnoreCase(String contractAddress);
}
