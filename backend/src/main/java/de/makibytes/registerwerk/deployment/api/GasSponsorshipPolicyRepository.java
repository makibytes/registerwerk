package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GasSponsorshipPolicyRepository extends JpaRepository<GasSponsorshipPolicy, UUID> {

    Optional<GasSponsorshipPolicy> findByAssetDeploymentIdAndActive(UUID assetDeploymentId, boolean active);

    Optional<GasSponsorshipPolicy> findByIssuerIdAndAssetDeploymentIdIsNullAndActive(UUID issuerId, boolean active);

    List<GasSponsorshipPolicy> findByIssuerId(UUID issuerId);
}
