package de.makibytes.registerwerk.corporateactions.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CorporateActionEntryRepository extends JpaRepository<CorporateActionEntry, UUID> {

    List<CorporateActionEntry> findByCorporateActionId(UUID corporateActionId);

    boolean existsByCorporateActionId(UUID corporateActionId);

    /** T3-02 / H6: does the action carry a held entry (nominee pool HELD_LOOK_THROUGH, or an ineligible holder
     *  HELD_BLOCKED) with a non-zero entitlement? Such an entitlement was not paid and has no automatic
     *  resolution path (PARK-T2-18 / operator task). */
    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM CorporateActionEntry e "
            + "WHERE e.corporateActionId = :caId AND e.payoutStatus <> 'PAYABLE' "
            + "AND e.entitlementAmount IS NOT NULL AND e.entitlementAmount <> 0")
    boolean existsHeldWithEntitlement(@Param("caId") UUID corporateActionId);

    /** An investor's settled entries within a date range — the input to Steuerbescheinigung /
     *  corporate-action confirmations. Only settled entries count as realized income. */
    @Query("SELECT e FROM CorporateActionEntry e WHERE e.investorId = :investorId "
            + "AND e.settledAt IS NOT NULL AND e.settledAt >= :from AND e.settledAt < :to")
    List<CorporateActionEntry> findSettledByInvestorAndPeriod(
            @Param("investorId") UUID investorId, @Param("from") Instant from, @Param("to") Instant to);
}
