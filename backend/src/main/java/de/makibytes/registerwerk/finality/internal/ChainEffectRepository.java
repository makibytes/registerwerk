package de.makibytes.registerwerk.finality.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ChainEffectRepository extends JpaRepository<ChainEffect, UUID> {

    Optional<ChainEffect> findBySourceEventKeyAndEffectTypeAndEntityId(
            String sourceEventKey, String effectType, UUID entityId);

    /** Atomic claim: proceeds only if this row is still ACTIVE (or a prior COMPENSATION_FAILED
     *  attempt, for the retry job), so two dispatchers racing on the same row never both run the
     *  compensator. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATING "
            + "WHERE c.id = :id AND c.status IN ("
            + "de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE, "
            + "de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATION_FAILED)")
    int claimForCompensation(@Param("id") UUID id);

    /** Distinct effect types among rows still ACTIVE — the startup self-check verifies each has a
     *  registered compensator. */
    @Query("SELECT DISTINCT c.effectType FROM ChainEffect c "
            + "WHERE c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    List<String> findDistinctActiveEffectTypes();

    /** Every effect recorded at or after a fork block, in any status — the generic retraction
     *  sweep ({@code BlockFinalityServiceImpl.recordRetraction}) compensates each one regardless of
     *  whether it is still ACTIVE or already SETTLED (a reorg deep enough to retract a FINALIZED
     *  block un-settles it too), relying on {@link de.makibytes.registerwerk.finality.api.ChainEffectCompensator}
     *  idempotency to make re-compensating an already-COMPENSATED row a safe no-op. */
    @Query("SELECT c.id FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId AND c.blockNumber >= :forkBlock")
    List<UUID> findIdsAtOrAfter(@Param("chainConfigId") UUID chainConfigId, @Param("forkBlock") long forkBlock);

    /** Marks every still-ACTIVE effect at exactly this height SETTLED — called once the block
     *  reaches FINALIZED, mirroring {@code token_transfer}'s own trust model (only PROVISIONAL/SAFE
     *  rows are continuously re-probed; FINALIZED is trusted once reached). Without this, the
     *  "unresolved effects" set watched by {@link #findIdsAtOrAfter}-driven re-probing would grow
     *  without bound. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChainEffect c SET c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.SETTLED "
            + "WHERE c.chainConfigId = :chainConfigId AND c.blockNumber = :blockNumber "
            + "AND c.status = de.makibytes.registerwerk.finality.internal.ChainEffect.Status.ACTIVE")
    int settleAtBlock(@Param("chainConfigId") UUID chainConfigId, @Param("blockNumber") long blockNumber);

    /** Distinct block numbers among effects not yet SETTLED (still ACTIVE, or stuck in a
     *  COMPENSATING/COMPENSATION_FAILED state) for a chain — merged into {@code ReorgGuard}'s
     *  unsettled-block walk so a block whose <em>only</em> activity was a module effect with no
     *  correlated {@code token_transfer} row (e.g. an admin action like a pause) is still
     *  re-probed for a reorg, not silently unwatched. */
    @Query("SELECT DISTINCT c.blockNumber FROM ChainEffect c WHERE c.chainConfigId = :chainConfigId "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.SETTLED "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.COMPENSATED "
            + "AND c.status <> de.makibytes.registerwerk.finality.internal.ChainEffect.Status.IRREVERSIBLE_ESCALATED")
    List<Long> findDistinctUnresolvedBlockNumbers(@Param("chainConfigId") UUID chainConfigId);
}
