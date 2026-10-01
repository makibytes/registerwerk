package de.makibytes.registerwerk.repo.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoQuoteRepository extends JpaRepository<RepoQuote, UUID> {
    List<RepoQuote> findByRfqIdOrderByRepoRateAscCreatedAtAsc(UUID rfqId);
    Optional<RepoQuote> findByRfqIdAndQuotingEntityIdAndStatus(UUID rfqId, UUID quotingEntityId, RepoTypes.QuoteStatus status);
    @org.springframework.data.jpa.repository.Query("select coalesce(max(q.quoteVersion), 0) from RepoQuote q where q.rfqId = :rfqId and q.quotingEntityId = :entityId")
    int maxVersion(@org.springframework.data.repository.query.Param("rfqId") UUID rfqId, @org.springframework.data.repository.query.Param("entityId") UUID entityId);
}

