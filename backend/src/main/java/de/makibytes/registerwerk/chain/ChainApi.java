package de.makibytes.registerwerk.chain;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.ChainConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for the chain configuration module.
 */
public interface ChainApi {

    Optional<ChainConfig> findChainConfig(UUID id);

    Optional<ChainConfig> findByIdentifier(String identifier);

    List<ChainConfig> findByChain(Chain chain, Network network);

    String explorerUrl(ChainConfig chainConfig, String txHash);
}
