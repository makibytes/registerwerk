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

    /** An investor's settled entries within a date range — the input to Steuerbescheinigung /
     *  corporate-action confirmations. Only settled entries count as realized income. */
    @Query("SELECT e FROM CorporateActionEntry e WHERE e.investorId = :investorId "
            + "AND e.settledAt IS NOT NULL AND e.settledAt >= :from AND e.settledAt < :to")
    List<CorporateActionEntry> findSettledByInvestorAndPeriod(
            @Param("investorId") UUID investorId, @Param("from") Instant from, @Param("to") Instant to);
}
