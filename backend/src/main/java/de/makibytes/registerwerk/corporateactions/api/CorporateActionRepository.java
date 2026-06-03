package de.makibytes.registerwerk.corporateactions.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    List<CorporateAction> findByAssetIdAndStatus(UUID assetId, CorporateAction.Status status);

    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status NOT IN ('SETTLED','CLOSED','CANCELLED') AND ca.paymentDate <= :date")
    List<CorporateAction> findDueForSettlement(@Param("date") LocalDate date);

    @Query("SELECT ca FROM CorporateAction ca WHERE ca.status = 'ANNOUNCED' AND ca.recordDate <= :today")
    List<CorporateAction> findReadyToCompute(@Param("today") LocalDate today);

    /**
     * Resolves the token standard of the asset linked to a corporate action without
     * importing from the {@code asset} module (avoids the asset ↔ blockchain Modulith cycle).
     * Returns null if the asset is not found.
     */
    @Query(value = "SELECT a.token_standard FROM asset a INNER JOIN corporate_action ca ON ca.asset_id = a.id WHERE ca.id = :corporateActionId", nativeQuery = true)
    String findTokenStandardByCorpAction(@Param("corporateActionId") UUID corporateActionId);
}
