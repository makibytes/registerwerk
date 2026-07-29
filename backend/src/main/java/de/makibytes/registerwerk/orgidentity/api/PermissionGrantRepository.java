package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, UUID> {

    List<PermissionGrant> findByOrgRegistrationIdOrderByCreatedAtDesc(UUID orgRegistrationId);

    List<PermissionGrant> findByStatus(PermissionGrantStatus status);

    List<PermissionGrant> findByStatusAndRevokedTxIsNull(PermissionGrantStatus status);

    List<PermissionGrant> findByPermissionDefinitionIdOrderByCreatedAtDesc(UUID permissionDefinitionId);

    List<PermissionGrant> findByPermissionDefinitionIdAndStatus(UUID permissionDefinitionId, PermissionGrantStatus status);

    Optional<PermissionGrant> findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatus(
            UUID permissionDefinitionId, UUID orgRegistrationId, PermissionGrantType grantType, PermissionGrantStatus status);

    Optional<PermissionGrant> findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndRoleCodeAndStatus(
            UUID permissionDefinitionId, UUID orgRegistrationId, PermissionGrantType grantType, String roleCode,
            PermissionGrantStatus status);
}
