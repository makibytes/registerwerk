package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import de.makibytes.registerwerk.finality.events.FinalityPolicyChangedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for the finality policy model — called by {@code finality.web.FinalityPolicyController}
 * (same module, different subpackage: not a cross-module dependency, so this stays a plain
 * {@code public} internal class rather than an {@code api} port, mirroring {@code
 * chain.internal.RpcNodeService}'s equivalent relationship to {@code chain.web.RpcNodeController}).
 *
 * <p>Every mutation evicts the entire {@code finalityPolicies} cache rather than a single key: a
 * GLOBAL or TOKEN_STANDARD assignment change can affect every asset that doesn't have a more
 * specific override, so there is no narrower correct eviction — policy changes are rare admin
 * actions, so the cost of a full clear is negligible.
 */
@Service
public class FinalityPolicyAdminService {

    private final FinalityPolicyAssignmentRepository assignmentRepository;
    private final FinalityPolicyOverrideRepository overrideRepository;
    private final ApplicationEventPublisher eventPublisher;

    FinalityPolicyAdminService(FinalityPolicyAssignmentRepository assignmentRepository,
            FinalityPolicyOverrideRepository overrideRepository, ApplicationEventPublisher eventPublisher) {
        this.assignmentRepository = assignmentRepository;
        this.overrideRepository = overrideRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<FinalityPolicyAssignmentView> listAssignments() {
        return assignmentRepository.findAllByOrderByScopeTypeAsc().stream()
                .map(FinalityPolicyAssignmentView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<FinalityPolicyOverrideView> listOverridesForAsset(UUID assetId) {
        return overrideRepository.findByAssetIdOrderByCreatedAtDesc(assetId).stream()
                .map(FinalityPolicyOverrideView::of).toList();
    }

    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public FinalityPolicyAssignmentView setGlobalProfile(FinalityPolicyProfile profile, UUID actorId, String actorRole) {
        FinalityPolicyAssignment assignment = assignmentRepository
                .findByScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL)
                .orElseGet(FinalityPolicyAssignment::new);
        assignment.setScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL);
        assignment.setProfile(profile);
        assignment.setCreatedBy(actorId);
        FinalityPolicyAssignment saved = assignmentRepository.save(assignment);
        publishChange("GLOBAL_PROFILE_SET", saved.getId(), actorId, actorRole,
                Map.of("profile", profile.name()));
        return FinalityPolicyAssignmentView.of(saved);
    }

    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public FinalityPolicyAssignmentView setTokenStandardProfile(
            TokenStandard tokenStandard, FinalityPolicyProfile profile, UUID actorId, String actorRole) {
        FinalityPolicyAssignment assignment = assignmentRepository
                .findByScopeTypeAndTokenStandard(FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD, tokenStandard)
                .orElseGet(FinalityPolicyAssignment::new);
        assignment.setScopeType(FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD);
        assignment.setTokenStandard(tokenStandard);
        assignment.setProfile(profile);
        assignment.setCreatedBy(actorId);
        FinalityPolicyAssignment saved = assignmentRepository.save(assignment);
        publishChange("TOKEN_STANDARD_PROFILE_SET", saved.getId(), actorId, actorRole,
                Map.of("tokenStandard", tokenStandard.name(), "profile", profile.name()));
        return FinalityPolicyAssignmentView.of(saved);
    }

    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public FinalityPolicyAssignmentView setAssetProfile(UUID assetId, FinalityPolicyProfile profile, UUID actorId, String actorRole) {
        FinalityPolicyAssignment assignment = assignmentRepository
                .findByScopeTypeAndAssetId(FinalityPolicyAssignment.ScopeType.ASSET, assetId)
                .orElseGet(FinalityPolicyAssignment::new);
        assignment.setScopeType(FinalityPolicyAssignment.ScopeType.ASSET);
        assignment.setAssetId(assetId);
        assignment.setProfile(profile);
        assignment.setCreatedBy(actorId);
        FinalityPolicyAssignment saved = assignmentRepository.save(assignment);
        publishChange("ASSET_PROFILE_SET", saved.getId(), actorId, actorRole,
                Map.of("assetId", assetId.toString(), "profile", profile.name()));
        return FinalityPolicyAssignmentView.of(saved);
    }

    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public void deleteAssignment(UUID assignmentId, UUID actorId, String actorRole) {
        FinalityPolicyAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new EntityNotFoundException("FinalityPolicyAssignment", assignmentId));
        assignmentRepository.delete(assignment);
        publishChange("ASSIGNMENT_DELETED", assignmentId, actorId, actorRole,
                Map.of("scopeType", assignment.getScopeType().name()));
    }

    /** @param reason mandatory, audited justification for lowering (or raising) an operation's
     *                required level for one specific asset. */
    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public FinalityPolicyOverrideView createOverride(UUID assetId, String operation, FinalityLevel requiredLevel,
            String reason, UUID actorId, String actorRole) {
        FinalityPolicyOverride override = overrideRepository.findByAssetIdAndOperation(assetId, operation)
                .orElseGet(FinalityPolicyOverride::new);
        override.setAssetId(assetId);
        override.setOperation(operation);
        override.setRequiredLevel(requiredLevel);
        override.setReason(reason);
        override.setCreatedBy(actorId);
        FinalityPolicyOverride saved = overrideRepository.save(override);
        publishChange("OVERRIDE_SET", assetId, actorId, actorRole,
                Map.of("operation", operation, "requiredLevel", requiredLevel.name(), "reason", reason));
        return FinalityPolicyOverrideView.of(saved);
    }

    @Transactional
    @CacheEvict(value = "finalityPolicies", allEntries = true)
    public void deleteOverride(UUID overrideId, UUID actorId, String actorRole) {
        FinalityPolicyOverride override = overrideRepository.findById(overrideId)
                .orElseThrow(() -> new EntityNotFoundException("FinalityPolicyOverride", overrideId));
        overrideRepository.delete(override);
        publishChange("OVERRIDE_DELETED", override.getAssetId(), actorId, actorRole,
                Map.of("operation", override.getOperation()));
    }

    private void publishChange(String changeType, UUID subjectId, UUID actorId, String actorRole,
            Map<String, Object> details) {
        eventPublisher.publishEvent(new FinalityPolicyChangedEvent(
                changeType, subjectId, actorId, actorRole, details, Instant.now()));
    }
}
