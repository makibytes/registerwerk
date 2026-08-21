package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VaultNavStrikeRepository extends JpaRepository<VaultNavStrike, UUID> {

    List<VaultNavStrike> findByAssetIdOrderByEffectiveAtDesc(UUID assetId);

    /** Strikes with a submitted tx not yet resolved — scoped so this shrinks over time instead of
     *  re-scanning every strike ever made (see {@code VaultConfirmationListener}). */
    List<VaultNavStrike> findByTxHashIsNotNullAndConfirmedFalse();
}
