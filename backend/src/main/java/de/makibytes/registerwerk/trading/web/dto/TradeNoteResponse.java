package de.makibytes.registerwerk.trading.web.dto;

import java.time.Instant;
import java.util.UUID;

public record TradeNoteResponse(UUID id, String actorRole, UUID actorEntityId, String text, Instant createdAt) {}
