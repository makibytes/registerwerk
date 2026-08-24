package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetVaultStateRepository extends JpaRepository<AssetVaultState, UUID> {

    /** Serializes confirmation and compensation of the same denormalized vault projection. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from AssetVaultState state where state.assetId = :assetId")
    Optional<AssetVaultState> findByAssetIdForUpdate(@Param("assetId") UUID assetId);

    /** Vault states with an in-flight {@code setDepositCap} tx not yet resolved — see
     *  {@code VaultConfirmationListener}. */
    List<AssetVaultState> findByDepositCapTxHashIsNotNull();
}
