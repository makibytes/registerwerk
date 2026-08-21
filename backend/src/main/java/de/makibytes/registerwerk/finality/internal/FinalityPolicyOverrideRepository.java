package de.makibytes.registerwerk.finality.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinalityPolicyOverrideRepository extends JpaRepository<FinalityPolicyOverride, UUID> {

    Optional<FinalityPolicyOverride> findByAssetIdAndOperation(UUID assetId, String operation);

    List<FinalityPolicyOverride> findByAssetIdOrderByCreatedAtDesc(UUID assetId);
}
