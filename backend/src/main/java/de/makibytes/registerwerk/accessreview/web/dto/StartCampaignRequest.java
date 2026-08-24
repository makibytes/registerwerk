package de.makibytes.registerwerk.accessreview.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record StartCampaignRequest(@NotBlank String name, LocalDate dueDate) {}
