package de.makibytes.registerwerk.trading.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DisputeTradeRequest(@NotBlank String reason) {}
