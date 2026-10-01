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
    List<RepoTrade> findByStatus(RepoTypes.TradeStatus status);
    List<RepoTrade> findByCollateralAssetIdAndStatusIn(UUID assetId, java.util.Collection<RepoTypes.TradeStatus> statuses);
    boolean existsByCollateralAssetIdAndStatusIn(UUID assetId, java.util.Collection<RepoTypes.TradeStatus> statuses);
    @Query("""
            select coalesce(sum(t.collateralQuantity), 0) from RepoTrade t
            where t.cashBorrowerEntityId = :entityId and t.collateralAssetId = :assetId and t.status in :statuses
            """)
    java.math.BigDecimal sumPledged(@Param("entityId") UUID entityId, @Param("assetId") UUID assetId,
                                    @Param("statuses") java.util.Collection<RepoTypes.TradeStatus> statuses);
}

