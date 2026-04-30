package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.endpoint.AddressEndpoint;
import de.makibytes.registerwerk.domain.endpoint.RiskLevel;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponse(
        UUID id,
        AddressEndpoint.OwnerType ownerType,
        UUID ownerId,
        String address,
        AddressEndpoint.AddressType addressType,
        String name,
        String notes,
        RiskLevel riskLevel,
        Instant createdAt,
        Instant updatedAt
) {
    public static EndpointResponse from(AddressEndpoint ep) {
        return new EndpointResponse(
                ep.getId(),
                ep.getOwnerType(),
                ep.getOwnerId(),
                ep.getAddress(),
                ep.getAddressType(),
                ep.getName(),
                ep.getNotes(),
                ep.getRiskLevel(),
                ep.getCreatedAt(),
                ep.getUpdatedAt()
        );
    }
}
