package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;

import java.time.Instant;
import java.util.UUID;

/** Read-shape of a {@link FinalityPolicyAssignment} row — {@code public} (unlike the entity
 *  itself) so {@code finality.web.FinalityPolicyController} can consume it across the
 *  subpackage boundary without exposing the JPA entity directly. */
public record FinalityPolicyAssignmentView(
        UUID id, String scopeType, TokenStandard tokenStandard, UUID assetId,
        FinalityPolicyProfile profile, Instant createdAt, Instant updatedAt) {

    static FinalityPolicyAssignmentView of(FinalityPolicyAssignment a) {
        return new FinalityPolicyAssignmentView(a.getId(), a.getScopeType().name(), a.getTokenStandard(),
                a.getAssetId(), a.getProfile(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
