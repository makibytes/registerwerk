package de.makibytes.registerwerk.indexer.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface SolanaMintSyncCursorRepository extends JpaRepository<SolanaMintSyncCursor, UUID> {

    Optional<SolanaMintSyncCursor> findByChainConfigIdAndMintAddress(UUID chainConfigId, String mintAddress);

    /** Full-resync support: forget every mint position of a chain; the next pass walks each mint from the start. */
    @Modifying
    @Query("UPDATE SolanaMintSyncCursor c SET c.lastSyncedSignature = NULL, c.lastSyncedAt = NULL WHERE c.chainConfigId = :chainConfigId")
    int clearPositions(@Param("chainConfigId") UUID chainConfigId);
}
