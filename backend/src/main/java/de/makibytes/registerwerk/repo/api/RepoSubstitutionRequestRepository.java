package de.makibytes.registerwerk.repo.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoSubstitutionRequestRepository extends JpaRepository<RepoSubstitutionRequest, UUID> {
    List<RepoSubstitutionRequest> findByRepoTradeIdOrderByRequestedAtAsc(UUID repoTradeId);
    List<RepoSubstitutionRequest> findByRepoTradeIdAndStatusIn(UUID repoTradeId, Collection<RepoTypes.SubstitutionStatus> statuses);
    Optional<RepoSubstitutionRequest> findFirstByRepoTradeIdAndStatusIn(UUID repoTradeId, Collection<RepoTypes.SubstitutionStatus> statuses);

    /** Replacement units of approved-but-unsettled substitutions the borrower will have to deliver. */
    @Query("""
            select coalesce(sum(s.quantity), 0) from RepoSubstitutionRequest s, RepoTrade t
            where s.repoTradeId = t.id and t.cashBorrowerEntityId = :entityId and s.assetId = :assetId
              and s.status = de.makibytes.registerwerk.repo.api.RepoTypes.SubstitutionStatus.APPROVED
            """)
    java.math.BigDecimal sumApprovedReplacement(@Param("entityId") UUID entityId, @Param("assetId") UUID assetId);
}
