package de.makibytes.registerwerk.admin.web.dto;

import java.time.Instant;
import java.util.UUID;

/** Customer-visible record of an operator's access to their entity. */
public record ImpersonationSessionView(
    UUID id, String mode, String reason, String ticketRef, UUID actorId, UUID approverId,
    Instant startedAt, Instant expiresAt, Instant endedAt, String endReason) {}
