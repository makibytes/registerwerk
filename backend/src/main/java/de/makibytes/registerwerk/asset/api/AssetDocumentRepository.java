package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetDocumentRepository extends JpaRepository<AssetDocument, UUID> {

    List<AssetDocument> findByAssetIdAndDeletedAtIsNull(UUID assetId);

    List<AssetDocument> findByAssetIdAndDocumentTypeAndDeletedAtIsNull(
            UUID assetId, AssetDocumentType documentType);

    boolean existsByAssetIdAndDeletedAtIsNull(UUID assetId);

    Optional<AssetDocument> findByIdAndAssetIdAndDeletedAtIsNull(UUID id, UUID assetId);
}
