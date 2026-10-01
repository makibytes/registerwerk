package de.makibytes.registerwerk.admin.events;

import java.util.UUID;

/** {@code resetLink} is sealed ({@code shared.SecureLinkPort}); the plaintext token is never published. */
public record OperatorUserPasswordResetNotificationEvent(
        UUID userId, String email, String displayName, String entityName, String resetLink) {
}
