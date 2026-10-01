package de.makibytes.registerwerk.trading.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisputeTradeRequest(@NotBlank @Size(max = 1000) String reason) {}
