package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.chain.RpcNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RpcNodeRepository extends JpaRepository<RpcNode, UUID> {

    List<RpcNode> findByChainConfig_Id(UUID chainConfigId);

    List<RpcNode> findByChainConfig_Identifier(String identifier);

    /** Eagerly joins chain_config to avoid N+1 during health checks. */
    @Query("SELECT n FROM RpcNode n JOIN FETCH n.chainConfig")
    List<RpcNode> findAllWithChainConfig();
}
