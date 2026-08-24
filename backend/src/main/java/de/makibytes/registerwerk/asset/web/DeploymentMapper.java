package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.asset.web.dto.DeploymentCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.DeploymentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link AssetDeployment} ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface DeploymentMapper {

    /**
     * Maps a {@link DeploymentCreateRequest} to an {@link AssetDeployment}.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "assetId", ignore = true)
    @Mapping(target = "contractAddress", ignore = true)
    @Mapping(target = "deployedAt", ignore = true)
    @Mapping(target = "deployedByTx", ignore = true)
    @Mapping(target = "deploymentStatus", ignore = true)
    @Mapping(target = "blockHash", ignore = true)
    @Mapping(target = "blockNumber", ignore = true)
    AssetDeployment toEntity(DeploymentCreateRequest request);

    /**
     * Maps an {@link AssetDeployment} to a {@link DeploymentResponse} DTO.
     */
    DeploymentResponse toResponse(AssetDeployment deployment);
}
