package de.makibytes.registerwerk.stepup.api;

import java.util.UUID;

/**
 * Programmatic 4-eyes gate for service/controller code that cannot use {@link RequiresStepUp} (for
 * example a decision that is only privileged for some inputs: granting REGISTRY_ADMIN but not TRADER).
 * It applies exactly what the annotation applies to the <em>current HTTP request</em>:
 * <ol>
 *   <li>the caller's step-up proof ({@code acr=stepup} in local TOTP mode, the Conditional Access
 *       context in Entra mode, 401 claims challenge) - skipped by {@link #requireIfNotBootstrap}
 *       only in the sense that it is still required, just without the approver;</li>
 *   <li>an {@code X-Dual-Control-Token} from a different, currently enabled REGISTRY_ADMIN or
 *       COMPLIANCE_OFFICER, minted for this {@code reason} <em>and this exact request</em> (method, path,
 *       query, plus the JSON body for body-bound reasons), valid for at most the dual-control window,
 *       and consumable once;</li>
 *   <li>a {@code DUAL_CONTROL_APPROVED} audit event, written before the caller continues.</li>
 * </ol>
 * Call it before mutating anything. It throws {@code AccessDeniedException} (403) when the proof is
 * missing or invalid; the approval is consumed when this method returns, so a later failure of the
 * guarded action needs a fresh approval. Must be called on the request thread.
 */
public interface DualControlGate {

    /** Step-up plus mandatory second approver. @return the approver's user id. */
    UUID require(String reason);

    /**
     * Bootstrap-aware variant: while fewer than two enabled, TOTP-enrolled REGISTRY_ADMINs exist, a
     * second approver cannot exist, so only the caller's step-up is required (otherwise the platform
     * could never create its second administrator). Whenever two exist, behaves like {@link #require}.
     * The outcome says which path was taken so the caller can flag {@code bootstrap=true} in its event.
     */
    Outcome requireIfNotBootstrap(String reason);

    /** @param approverId null on the bootstrap path */
    record Outcome(UUID approverId, boolean bootstrap) {}
}
