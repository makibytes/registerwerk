package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.AssetDocumentContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssetDocumentContentRepository extends JpaRepository<AssetDocumentContent, UUID> {
}
