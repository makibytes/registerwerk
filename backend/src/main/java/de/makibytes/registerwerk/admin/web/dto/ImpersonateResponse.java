package de.makibytes.registerwerk.admin.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImpersonateResponse(
    String token,
    String tokenType,
    OffsetDateTime expiresAt,
    UUID entityId,
    String entityName,
    String handoffUrl
) {}
