package de.makibytes.registerwerk.web.mapper;

import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.web.dto.AssetCreateRequest;
import de.makibytes.registerwerk.web.dto.AssetResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link Asset} ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface AssetMapper {

    /**
     * Maps an {@link AssetCreateRequest} to an {@link Asset} domain object.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "assetNumber", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "termsheetDocId", ignore = true)
    @Mapping(target = "publicData", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Asset toEntity(AssetCreateRequest request);

    /**
     * Maps an {@link Asset} domain object to an {@link AssetResponse} DTO.
     */
    AssetResponse toResponse(Asset asset);
}
