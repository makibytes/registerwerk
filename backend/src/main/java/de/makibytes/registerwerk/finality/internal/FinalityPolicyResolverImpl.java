package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import de.makibytes.registerwerk.finality.api.FinalityPolicyService;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
class FinalityPolicyResolverImpl implements FinalityPolicyService {

    private final FinalityPolicyAssignmentRepository assignmentRepository;
    private final FinalityPolicyOverrideRepository overrideRepository;

    FinalityPolicyResolverImpl(FinalityPolicyAssignmentRepository assignmentRepository,
            FinalityPolicyOverrideRepository overrideRepository) {
        this.assignmentRepository = assignmentRepository;
        this.overrideRepository = overrideRepository;
    }

    @Override
    @Cacheable(value = "finalityPolicies", key = "#operation + ':' + #assetId + ':' + #tokenStandard")
    public FinalityLevel requiredLevel(GatedOperation operation, UUID assetId, TokenStandard tokenStandard) {
        Optional<FinalityPolicyOverride> override =
                overrideRepository.findByAssetIdAndOperation(assetId, operation.name());
        if (override.isPresent()) {
            return FinalityPolicyDefaults.clamp(operation, override.get().getRequiredLevel());
        }

        Optional<FinalityPolicyAssignment> assetAssignment = assignmentRepository
                .findByScopeTypeAndAssetId(FinalityPolicyAssignment.ScopeType.ASSET, assetId);
        if (assetAssignment.isPresent()) {
            return resolveFromProfile(operation, assetAssignment.get().getProfile());
        }

        if (tokenStandard != null) {
            Optional<FinalityPolicyAssignment> standardAssignment = assignmentRepository
                    .findByScopeTypeAndTokenStandard(FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD, tokenStandard);
            if (standardAssignment.isPresent()) {
                return resolveFromProfile(operation, standardAssignment.get().getProfile());
            }
        }

        Optional<FinalityPolicyAssignment> globalAssignment =
                assignmentRepository.findByScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL);
        if (globalAssignment.isPresent()) {
            return resolveFromProfile(operation, globalAssignment.get().getProfile());
        }

        // Bottom rung: the compiled-in default profile, not a seed row.
        return resolveFromProfile(operation, FinalityPolicyProfile.BALANCED);
    }

    private static FinalityLevel resolveFromProfile(GatedOperation operation, FinalityPolicyProfile profile) {
        return FinalityPolicyDefaults.clamp(operation, FinalityPolicyDefaults.nominalLevel(profile, operation));
    }
}
