package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetVaultStateRepository extends JpaRepository<AssetVaultState, UUID> {

    /** Vault states with an in-flight {@code setDepositCap} tx not yet resolved — see
     *  {@code VaultConfirmationListener}. */
    List<AssetVaultState> findByDepositCapTxHashIsNotNull();
}
