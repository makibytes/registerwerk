package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.api.RpcNode;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RpcNodeRepository extends JpaRepository<RpcNode, UUID> {

    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig WHERE n.chainConfig.id = :chainConfigId")
    List<RpcNode> findByChainConfig_IdWithChainConfig(@Param("chainConfigId") UUID chainConfigId);

    List<RpcNode> findByChainConfig_Identifier(String identifier);

    /**
     * Eagerly joins chain_config to avoid N+1 during health checks.
     *
     * <p>Returns every node, including disabled ones and nodes of disabled chains, because
     * {@code BlockchainClientRegistry.refreshFromNodes} needs the complete picture — a chain whose
     * nodes are <em>all</em> disabled must stay present in the pool so lookups fail loudly rather
     * than silently falling through to the legacy single-client tier. Deciding which of these to
     * actually probe is {@code RpcNodeHealthService}'s job.
     */
    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig")
    List<RpcNode> findAllWithChainConfig();

    /** Backs the RPC-node-health alerting gauge — checkAllNodes() already persists this
     *  status every ~30s via saveAll(), but nothing ever counted it. */
    long countByHealthyFalse();

    /** Backs the {@code registerwerk_chaincache_nodes_total} gauge — one series per
     *  {@link RpcNode.NodeKind}, so the operator dashboard can show the CHAINCACHE-vs-DIRECT_RPC
     *  split across the whole fleet at a glance. */
    long countByKind(RpcNode.NodeKind kind);

    /** Eagerly joins chain_config — {@code RpcNodeService.getById} feeds every write operation
     *  (enable/disable/pin/delete/update/redetect), several of which (update, redetect) return the
     *  entity for {@code toResponse()} to serialize in the controller, outside this method's own
     *  transaction; a lazy {@code chainConfig} proxy would throw LazyInitializationException there. */
    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig WHERE n.id = :id AND n.chainConfig.id = :chainConfigId")
    Optional<RpcNode> findByIdAndChainConfig_Id(@Param("id") UUID id, @Param("chainConfigId") UUID chainConfigId);

    /** The chaincache connection to use for a chain that opted into
     *  {@code ChainConfig.FinalitySource.CHAINCACHE} — see
     *  {@code blockchain.internal.ChaincacheDurableStreamManager}. If more than one is enabled,
     *  any one is usable (chaincache's durable stream is keyed by consumerId+stream, not by which
     *  node row asked for it). */
    List<RpcNode> findByChainConfig_IdAndKindAndEnabledTrue(UUID chainConfigId, RpcNode.NodeKind kind);

    /** Whether {@code chainConfigId} has at least one enabled {@code CHAINCACHE}-kind node —
     *  the sole input to auto-deriving {@code ChainConfig.finalitySource}; see
     *  {@code RpcNodeService#recomputeFinalitySource}. */
    boolean existsByChainConfig_IdAndKindAndEnabledTrue(UUID chainConfigId, RpcNode.NodeKind kind);

    /**
     * Targeted update of only the health-check-owned columns, bypassing JPA's full-entity
     * {@code save()} — {@code RpcNodeHealthService.checkAll()} batch-loads every node, spends
     * seconds probing them all over the network, then used to {@code saveAll} the whole detached
     * batch back; any admin edit (kind/label/enabled/exclusive/url, or this class's own
     * kind/capabilities detection) landing in that window was silently overwritten with the
     * stale in-memory copy the health check started with. A full optimistic-locking (@Version)
     * fix would help every field; this narrower fix only needs the specific columns each writer
     * legitimately owns to never step on each other, which is enough for the two real writers
     * that exist today.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RpcNode n SET
                n.latestBlockNumber = :latestBlockNumber,
                n.blockLastAdvancedAt = :blockLastAdvancedAt,
                n.lastCheckedAt = :lastCheckedAt,
                n.lastSuccessAt = :lastSuccessAt,
                n.healthy = :healthy,
                n.consecutiveFailures = :consecutiveFailures,
                n.lagFromBest = :lagFromBest,
                n.syncing = :syncing
            WHERE n.id = :id
            """)
    void updateHealthFields(@Param("id") UUID id,
            @Param("latestBlockNumber") Long latestBlockNumber,
            @Param("blockLastAdvancedAt") Instant blockLastAdvancedAt,
            @Param("lastCheckedAt") Instant lastCheckedAt,
            @Param("lastSuccessAt") Instant lastSuccessAt,
            @Param("healthy") boolean healthy,
            @Param("consecutiveFailures") int consecutiveFailures,
            @Param("lagFromBest") Integer lagFromBest,
            @Param("syncing") boolean syncing);

    /**
     * Targeted update of only the chaincache-detection-owned columns (the symmetric counterpart
     * to {@link #updateHealthFields} — the periodic redetection job and the manual redetect
     * action both write only these, never the health fields, for the same clobbering-avoidance
     * reason). {@code capabilitiesJson} is pre-serialized JSON text (or {@code null}), cast to
     * {@code jsonb} natively — a JPQL {@code UPDATE} cannot target a JSONB-mapped column. Always
     * resets {@code chaincache_probe_failures} to 0 — every caller of this method is a definitive
     * outcome (a confirmed match, a reachable-but-no-longer-matching demotion, or a
     * hysteresis-exhausted demotion), never the "one more transient failure" case that
     * {@link #incrementChaincacheProbeFailures} handles instead.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE rpc_node SET
                kind = :kind,
                management_url = :managementUrl,
                remote_chain_key = :remoteChainKey,
                capabilities = CAST(:capabilitiesJson AS jsonb),
                chaincache_probe_failures = 0
            WHERE id = :id
            """)
    void updateChaincacheDetection(@Param("id") UUID id, @Param("kind") String kind,
            @Param("managementUrl") String managementUrl, @Param("remoteChainKey") String remoteChainKey,
            @Param("capabilitiesJson") String capabilitiesJson);

    /**
     * Records one more transient redetection failure for a currently-{@code CHAINCACHE} node
     * without touching its kind/managementUrl/remoteChainKey/capabilities — see
     * {@code RpcNodeService#redetectAll}'s demotion hysteresis: only a genuinely reachable
     * mismatch or the third consecutive unreachable failure actually demotes the node, so a lone
     * network blip against an otherwise-fine chaincache connection never tears down its durable
     * event stream (which {@code ChaincacheDurableStreamManager} closes the moment
     * {@code finalitySource} flips away from {@code CHAINCACHE}).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RpcNode n SET n.chaincacheProbeFailures = n.chaincacheProbeFailures + 1 WHERE n.id = :id")
    void incrementChaincacheProbeFailures(@Param("id") UUID id);
}
