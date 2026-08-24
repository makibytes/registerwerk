package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VaultNavStrikeRepository extends JpaRepository<VaultNavStrike, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select strike from VaultNavStrike strike where strike.id = :id")
    Optional<VaultNavStrike> findByIdForUpdate(@Param("id") UUID id);

    List<VaultNavStrike> findByAssetIdOrderByEffectiveAtDesc(UUID assetId);

    /** Strikes with a submitted tx not yet resolved — scoped so this shrinks over time instead of
     *  re-scanning every strike ever made (see {@code VaultConfirmationListener}). */
    List<VaultNavStrike> findByTxHashIsNotNullAndConfirmedFalse();
}
