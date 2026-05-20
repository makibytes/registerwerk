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
}
