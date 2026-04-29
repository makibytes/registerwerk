package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.endpoint.AddressEndpoint;

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
                ep.getCreatedAt(),
                ep.getUpdatedAt()
        );
    }
}
