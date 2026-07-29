package de.makibytes.registerwerk.blockchain.internal.confidential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ConfidentialTransferScreeningStateRepository extends JpaRepository<ConfidentialTransferScreeningState, UUID> {

    Optional<ConfidentialTransferScreeningState> findByAssetDeploymentId(UUID assetDeploymentId);
}
