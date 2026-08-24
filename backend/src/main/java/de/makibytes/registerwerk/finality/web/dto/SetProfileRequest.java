package de.makibytes.registerwerk.finality.web.dto;

import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import jakarta.validation.constraints.NotNull;

public record SetProfileRequest(@NotNull FinalityPolicyProfile profile) {
}
