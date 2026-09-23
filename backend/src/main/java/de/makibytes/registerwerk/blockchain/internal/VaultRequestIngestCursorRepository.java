package de.makibytes.registerwerk.blockchain.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface VaultRequestIngestCursorRepository extends JpaRepository<VaultRequestIngestCursor, UUID> {
}
