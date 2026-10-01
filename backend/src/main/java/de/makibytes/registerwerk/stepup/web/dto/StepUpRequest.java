package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

public record StepUpRequest(
        @NotBlank String code,
        String method,   // "TOTP" (default) or "WEBAUTHN"
        // The @RequiresStepUp(reason=...) value of the action this token will be used to approve, as a
        // dual-control approver. Embedded as `stepup_scope`; StepUpTokenValidator requires an exact match.
        String action,
        // K3 (6-08): "METHOD /path?query" of the exact request being approved, e.g.
        // "POST /api/v1/token-admin/<id>/force-burn". Required together with `action`; embedded as the
        // `stepup_target` digest so the approval cannot be used for any other request.
        String target,
        // The JSON body of that request, only for reasons whose approval also covers the body
        // (mint, burn, forced transfer; see registerwerk.auth.step-up.dual-control.bind-body-reasons).
        JsonNode targetBody
) {
    public StepUpRequest(String code, String method, String action) {
        this(code, method, action, null, null);
    }
}
