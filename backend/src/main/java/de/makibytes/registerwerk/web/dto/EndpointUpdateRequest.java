package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.endpoint.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EndpointUpdateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String notes,
        RiskLevel riskLevel
) {}
