package de.makibytes.registerwerk.endpoint.web.dto;

import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EndpointCreateRequest(
        @NotBlank String address,
        @NotNull AddressEndpoint.AddressType addressType,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String notes,
        RiskLevel riskLevel
) {}
