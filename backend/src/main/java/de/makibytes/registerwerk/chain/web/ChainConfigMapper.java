package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.web.dto.ChainConfigCreateRequest;
import de.makibytes.registerwerk.chain.web.dto.ChainConfigResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link ChainConfig} ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface ChainConfigMapper {

    /**
     * Maps a {@link ChainConfig} entity to a {@link ChainConfigResponse} DTO.
     * Enum values are mapped to their string names.
     */
    @Mapping(target = "chainType",   expression = "java(config.getChainType().name())")
    @Mapping(target = "networkType", expression = "java(config.getNetworkType().name())")
    ChainConfigResponse toResponse(ChainConfig config);

    /**
     * Maps a {@link ChainConfigCreateRequest} DTO to a {@link ChainConfig} entity.
     * The {@code id}, {@code createdAt}, and {@code updatedAt} fields are managed by JPA.
     */
    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    @Mapping(target = "updatedAt",       ignore = true)
    @Mapping(target = "enabled",         constant = "true")
    @Mapping(target = "fallbackRpcUrls", ignore = true)
    @Mapping(target = "fallbackRpcUrlList", ignore = true)
    @Mapping(target = "chainType",
             expression = "java(ChainConfig.ChainType.valueOf(request.chainType().toUpperCase()))")
    @Mapping(target = "networkType",
             expression = "java(ChainConfig.NetworkType.valueOf(request.networkType().toUpperCase()))")
    ChainConfig toEntity(ChainConfigCreateRequest request);
}
