package de.makibytes.registerwerk.admin.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deliberately carries no bearer token: the handoff URL holds a one-time code (valid ~60 s) that the
 * customer app exchanges for an httpOnly session cookie, so the token cannot be copied from here.
 */
public record ImpersonateResponse(
    UUID sessionId,
    String mode,
    OffsetDateTime expiresAt,
    UUID entityId,
    String entityName,
    String handoffUrl
) {}
