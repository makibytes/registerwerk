package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinalityPolicyAssignmentRepository extends JpaRepository<FinalityPolicyAssignment, UUID> {

    Optional<FinalityPolicyAssignment> findByScopeType(FinalityPolicyAssignment.ScopeType scopeType);

    Optional<FinalityPolicyAssignment> findByScopeTypeAndTokenStandard(
            FinalityPolicyAssignment.ScopeType scopeType, TokenStandard tokenStandard);

    Optional<FinalityPolicyAssignment> findByScopeTypeAndAssetId(
            FinalityPolicyAssignment.ScopeType scopeType, UUID assetId);

    /** All assignments, for the admin listing endpoint. */
    List<FinalityPolicyAssignment> findAllByOrderByScopeTypeAsc();
}
