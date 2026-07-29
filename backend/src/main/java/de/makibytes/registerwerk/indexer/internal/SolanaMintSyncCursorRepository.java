package de.makibytes.registerwerk.indexer.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SolanaMintSyncCursorRepository extends JpaRepository<SolanaMintSyncCursor, UUID> {

    Optional<SolanaMintSyncCursor> findByChainConfigIdAndMintAddress(UUID chainConfigId, String mintAddress);
}
