package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.constraints.Future;

import java.time.Instant;

public record ClaimExpiryRequest(@Future Instant expiresAt) {}
