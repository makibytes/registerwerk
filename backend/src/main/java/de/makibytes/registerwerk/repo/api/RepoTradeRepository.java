package de.makibytes.registerwerk.repo.api;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface RepoTradeRepository extends JpaRepository<RepoTrade, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from RepoTrade t where t.id=:id")
    Optional<RepoTrade> findByIdForUpdate(@Param("id") UUID id);
    @Query("select t from RepoTrade t where t.cashBorrowerEntityId=:entityId or t.cashLenderEntityId=:entityId order by t.createdAt desc")
    List<RepoTrade> findByParty(@Param("entityId") UUID entityId);
    Optional<RepoTrade> findByRfqId(UUID rfqId);
}

