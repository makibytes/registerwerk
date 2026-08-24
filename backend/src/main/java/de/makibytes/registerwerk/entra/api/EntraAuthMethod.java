package de.makibytes.registerwerk.entra.api;

import java.time.Instant;

/**
 * One registered authentication method, as returned by
 * {@code GET /users/{id}/authentication/methods}.
 *
 * @param id           Graph method id, used for a targeted DELETE
 * @param type         the method kind
 * @param displayName  human-readable label (device name, masked phone number, …); may be null
 * @param isDefault    whether Entra treats this as the user's default MFA method — deletion
 *                     ordering depends on it, see {@code EntraDirectoryPort#resetAllAuthMethods}
 * @param createdAt    registration timestamp where Graph reports one; may be null
 */
public record EntraAuthMethod(
        String id,
        EntraAuthMethodType type,
        String displayName,
        boolean isDefault,
        Instant createdAt) {
}
