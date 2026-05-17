package de.makibytes.registerwerk.admin.events;

import java.util.UUID;

public record OperatorUserPasswordResetNotificationEvent(UUID userId, String email, String resetLink) {
}
