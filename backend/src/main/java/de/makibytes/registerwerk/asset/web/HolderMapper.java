package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.api.AssetHolder;
import de.makibytes.registerwerk.asset.web.dto.HolderCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.HolderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link AssetHolder} ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface HolderMapper {

    /**
     * Maps a {@link HolderCreateRequest} to an {@link AssetHolder}.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "assetId", ignore = true)
    @Mapping(target = "whitelisted", ignore = true)
    @Mapping(target = "whitelistTxHash", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AssetHolder toEntity(HolderCreateRequest request);

    /**
     * Maps an {@link AssetHolder} to a {@link HolderResponse} DTO.
     */
    @Mapping(target = "externalId", constant = "")
    HolderResponse toResponse(AssetHolder holder);
}
