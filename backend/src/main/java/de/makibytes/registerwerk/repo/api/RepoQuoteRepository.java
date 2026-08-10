package de.makibytes.registerwerk.repo.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoQuoteRepository extends JpaRepository<RepoQuote, UUID> {
    List<RepoQuote> findByRfqIdOrderByRepoRateAscCreatedAtAsc(UUID rfqId);
    Optional<RepoQuote> findByRfqIdAndQuotingEntityId(UUID rfqId, UUID quotingEntityId);
}

