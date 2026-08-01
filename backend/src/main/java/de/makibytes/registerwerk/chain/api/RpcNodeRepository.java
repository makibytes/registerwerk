package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.api.RpcNode;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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
}
