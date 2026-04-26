package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.AssetDocument;
import de.makibytes.registerwerk.domain.enums.AssetDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetDocumentRepository extends JpaRepository<AssetDocument, UUID> {

    List<AssetDocument> findByAssetIdAndDeletedAtIsNull(UUID assetId);

    List<AssetDocument> findByAssetIdAndDocumentTypeAndDeletedAtIsNull(
            UUID assetId, AssetDocumentType documentType);

    boolean existsByAssetIdAndDeletedAtIsNull(UUID assetId);
}
