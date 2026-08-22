package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ChainConfig} entities.
 */
public interface ChainConfigRepository extends JpaRepository<ChainConfig, UUID> {

    /** Returns all enabled chain configurations, regardless of chain type. */
    List<ChainConfig> findByEnabledTrue();

    /** Returns all enabled configurations for a specific chain type (EVM or SOLANA). */
    List<ChainConfig> findByChainTypeAndEnabledTrue(ChainConfig.ChainType chainType);

    /** Looks up a chain configuration by its stable machine-readable identifier. */
    Optional<ChainConfig> findByIdentifier(String identifier);

    /** Returns all enabled configurations for a specific network type (MAINNET or TESTNET). */
    List<ChainConfig> findByNetworkTypeAndEnabledTrue(ChainConfig.NetworkType networkType);

    /** Returns all chain configurations matching the identifier pattern (e.g. "ETHEREUM_MAINNET"). */
    List<ChainConfig> findByIdentifierStartingWith(String prefix);

    /** Chains that opted into chaincache's push-based durable retraction stream instead of this
     *  registry's own poll-based probing — see {@code blockchain.internal.ChaincacheDurableStreamManager}. */
    List<ChainConfig> findByEnabledTrueAndFinalitySource(ChainConfig.FinalitySource finalitySource);

    /** Targeted update of only {@code finalitySource} — {@code RpcNodeService} recomputes this on
     *  every node add/update/enable/disable/delete/redetect (fully auto-derived, never operator-set;
     *  see {@code ChainConfig.FinalitySource}'s javadoc), which would otherwise race a concurrent
     *  {@code ChainConfigService.update}'s full-entity save over unrelated fields (displayName,
     *  rpcUrl, ...) the same way {@code RpcNodeRepository.updateHealthFields}'s javadoc describes. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChainConfig c SET c.finalitySource = :finalitySource WHERE c.id = :id")
    void updateFinalitySource(@Param("id") UUID id, @Param("finalitySource") ChainConfig.FinalitySource finalitySource);
}
