package de.makibytes.registerwerk.admin.events;

import java.util.UUID;

/** {@code inviteLink} is sealed ({@code shared.SecureLinkPort}); the plaintext token is never published. */
public record OperatorUserInvitedNotificationEvent(
        UUID userId, String email, String displayName, String entityName, String inviteLink) {
}
