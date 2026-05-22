package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssetVaultStateRepository extends JpaRepository<AssetVaultState, UUID> {
}
