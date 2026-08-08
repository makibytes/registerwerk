package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.web.dto.AssetCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.AssetResponse;
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
    @Mapping(target = "entryType", ignore = true)
    @Mapping(target = "lastHolderSyncTime", ignore = true)
    @Mapping(target = "targetMarketCategories", ignore = true)
    @Mapping(target = "targetMarketMinExperience", ignore = true)
    @Mapping(target = "minInvestmentAmount", ignore = true)
    @Mapping(target = "maxHoldingAmount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Asset toEntity(AssetCreateRequest request);

    /**
     * Maps an {@link Asset} domain object to an {@link AssetResponse} DTO.
     * hasTermSheet is always false here; callers that need the live value must
     * use AssetController#buildResponse(Asset) which queries AssetDocumentRepository.
     */
    @Mapping(target = "hasTermSheet", constant = "false")
    @Mapping(target = "externalId", constant = "")
    AssetResponse toResponse(Asset asset);
}
