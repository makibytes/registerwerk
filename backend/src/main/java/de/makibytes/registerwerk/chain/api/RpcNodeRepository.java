package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.api.RpcNode;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** Eagerly joins chain_config — {@code RpcNodeService.getById} feeds every write operation
     *  (enable/disable/pin/delete/update/refresh-capabilities), several of which (update,
     *  refresh-capabilities) return the entity for {@code toResponse()} to serialize in the
     *  controller, outside this method's own transaction; a lazy {@code chainConfig} proxy would
     *  throw LazyInitializationException there. */
    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig WHERE n.id = :id AND n.chainConfig.id = :chainConfigId")
    Optional<RpcNode> findByIdAndChainConfig_Id(@Param("id") UUID id, @Param("chainConfigId") UUID chainConfigId);

    /** The chaincache connection to use for a chain that opted into
     *  {@code ChainConfig.FinalitySource.CHAINCACHE} — see
     *  {@code blockchain.internal.ChaincacheDurableStreamManager}. If more than one is enabled,
     *  any one is usable (chaincache's durable stream is keyed by consumerId+stream, not by which
     *  node row asked for it). */
    List<RpcNode> findByChainConfig_IdAndKindAndEnabledTrue(UUID chainConfigId, RpcNode.NodeKind kind);
}
