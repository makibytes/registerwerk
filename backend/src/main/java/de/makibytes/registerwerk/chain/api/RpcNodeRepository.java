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

    /** Eagerly joins chain_config to avoid N+1 during health checks. */
    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig")
    List<RpcNode> findAllWithChainConfig();
}
