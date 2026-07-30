package de.makibytes.registerwerk.entra.api;

/**
 * A Conditional Access authentication context (c1–c99) as defined in the tenant.
 *
 * <p>Microsoft's guidance is explicitly not to hard-code these ids, so Registerwerk keeps the
 * id in configuration and verifies it against
 * {@code GET /identity/conditionalAccess/authenticationContextClassReferences} at boot.
 *
 * @param id          e.g. {@code "c1"}
 * @param displayName tenant-defined label
 * @param isAvailable Entra's "published to apps" flag — an unpublished context is invisible to
 *                    resources and can never be satisfied, which manifests as a redirect loop
 */
public record AuthContextRef(String id, String displayName, boolean isAvailable) {
}
