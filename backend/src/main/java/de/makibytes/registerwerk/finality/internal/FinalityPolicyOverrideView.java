package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;

import java.time.Instant;
import java.util.UUID;

/** Read-shape of a {@link FinalityPolicyOverride} row — see {@link FinalityPolicyAssignmentView}
 *  for why this is a separate public record rather than exposing the entity. */
public record FinalityPolicyOverrideView(
        UUID id, UUID assetId, String operation, FinalityLevel requiredLevel,
        String reason, UUID createdBy, Instant createdAt) {

    static FinalityPolicyOverrideView of(FinalityPolicyOverride o) {
        return new FinalityPolicyOverrideView(o.getId(), o.getAssetId(), o.getOperation(),
                o.getRequiredLevel(), o.getReason(), o.getCreatedBy(), o.getCreatedAt());
    }
}
