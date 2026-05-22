package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link LegalEntity} ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface EntityMapper {

    /**
     * Maps an {@link EntityCreateRequest} to a {@link LegalEntity} domain object.
     * The entityNumber, status, kycStatus, and audit fields are set by the service.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "entityNumber", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "kycStatus", ignore = true)
    @Mapping(target = "kycExpiryDate", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "idpIssuerUrl", ignore = true)
    @Mapping(target = "idpClientId", ignore = true)
    @Mapping(target = "idpClientSecret", ignore = true)
    LegalEntity toEntity(EntityCreateRequest request);

    /**
     * Maps a {@link LegalEntity} to an {@link EntityResponse} DTO.
     */
    @Mapping(target = "externalId", constant = "")
    EntityResponse toResponse(LegalEntity entity);
}
