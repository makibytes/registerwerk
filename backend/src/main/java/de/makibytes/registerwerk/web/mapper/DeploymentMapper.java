package de.makibytes.registerwerk.web.mapper;

import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.web.dto.DeploymentCreateRequest;
import de.makibytes.registerwerk.web.dto.DeploymentResponse;
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
    AssetDeployment toEntity(DeploymentCreateRequest request);

    /**
     * Maps an {@link AssetDeployment} to a {@link DeploymentResponse} DTO.
     */
    DeploymentResponse toResponse(AssetDeployment deployment);
}
