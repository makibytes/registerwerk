package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.asset.api.AssetDocumentContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssetDocumentContentRepository extends JpaRepository<AssetDocumentContent, UUID> {
}
