package de.makibytes.registerwerk.admin.events;

import java.util.UUID;

public record OperatorUserInvitedNotificationEvent(
        UUID userId, String email, String displayName, String inviteLink) {
}
