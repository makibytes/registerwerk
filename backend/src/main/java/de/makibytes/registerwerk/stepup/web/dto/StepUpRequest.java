package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;

public record StepUpRequest(
        @NotBlank String code,
        String method,   // "TOTP" (default) or "WEBAUTHN"
        // The @RequiresStepUp(reason=...) value of the action this token will be used to
        // approve, e.g. as a dual-control approver. When present, it is
        // embedded in the minted token as a `stepup_scope` claim; StepUpTokenValidator then
        // requires an exact match before accepting the token as a dual-control approval, so a
        // captured token can no longer approve a different action than the one it was minted
        // for. Optional for backward compatibility with callers not yet passing it.
        String action
) {}
