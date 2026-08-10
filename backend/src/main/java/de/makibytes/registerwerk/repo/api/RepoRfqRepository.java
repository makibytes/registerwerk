package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRfqRepository extends JpaRepository<RepoRfq, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RepoRfq r where r.id = :id")
    Optional<RepoRfq> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select distinct r from RepoRfq r left join r.targetEntityIds targetId
            where r.requesterEntityId = :entityId
               or r.visibility = de.makibytes.registerwerk.repo.api.RepoTypes.Visibility.BROADCAST
               or targetId = :entityId
            order by r.createdAt desc
            """)
    List<RepoRfq> findVisibleTo(@Param("entityId") UUID entityId);
}

