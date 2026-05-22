package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.ChainApi;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class ChainApiImpl implements ChainApi {

    private final ChainConfigRepository repository;
    private final ExplorerUrlBuilder explorerUrlBuilder;

    ChainApiImpl(ChainConfigRepository repository, ExplorerUrlBuilder explorerUrlBuilder) {
        this.repository = repository;
        this.explorerUrlBuilder = explorerUrlBuilder;
    }

    @Override
    public Optional<ChainConfig> findChainConfig(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<ChainConfig> findByIdentifier(String identifier) {
        return repository.findByIdentifier(identifier);
    }

    @Override
    public List<ChainConfig> findByChain(Chain chain, Network network) {
        String identifier = chain.name() + "_" + network.name();
        return repository.findByIdentifier(identifier)
                .map(java.util.List::of)
                .orElse(java.util.List.of());
    }

    @Override
    public String explorerUrl(ChainConfig chainConfig, String txHash) {
        return explorerUrlBuilder.buildTxUrl(chainConfig, txHash);
    }
}
