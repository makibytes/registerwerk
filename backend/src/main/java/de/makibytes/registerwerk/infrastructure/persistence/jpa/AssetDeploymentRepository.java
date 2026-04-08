package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetDeploymentRepository extends JpaRepository<AssetDeployment, UUID> {

    List<AssetDeployment> findByAssetId(UUID assetId);

    List<AssetDeployment> findByChainAndNetwork(Chain chain, Network network);

    Optional<AssetDeployment> findByChainAndNetworkAndContractAddress(
        Chain chain, Network network, String contractAddress);
}
