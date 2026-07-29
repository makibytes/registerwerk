package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, UUID> {

    Optional<PermissionDefinition> findByCode(String code);

    boolean existsByCode(String code);

    List<PermissionDefinition> findByDappListingIdAndStatus(UUID dappListingId, PermissionDefinitionStatus status);
}
