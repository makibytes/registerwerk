package de.makibytes.registerwerk.trading.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddTradeNoteRequest(@NotBlank @Size(max = 2000) String text) {}
