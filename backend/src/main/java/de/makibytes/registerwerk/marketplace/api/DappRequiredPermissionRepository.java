package de.makibytes.registerwerk.marketplace.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DappRequiredPermissionRepository extends JpaRepository<DappRequiredPermission, UUID> {

    List<DappRequiredPermission> findByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);
}
