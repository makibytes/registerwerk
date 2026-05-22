package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.events.ChainAddedEvent;
import de.makibytes.registerwerk.chain.events.ChainUpdatedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for managing dynamic chain configurations.
 * Handles CRUD operations, enable/disable toggling, and triggers a blockchain client
 * refresh when the registry changes.
 */
@Service
@Transactional
public class ChainConfigService {

    private static final Logger log = LoggerFactory.getLogger(ChainConfigService.class);

    private final ChainConfigRepository chainConfigRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public ChainConfigService(
            ChainConfigRepository chainConfigRepository,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.chainConfigRepository = chainConfigRepository;
        this.eventPublisher = eventPublisher;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChainConfig> listAll() {
        return chainConfigRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ChainConfig> listEnabled() {
        return chainConfigRepository.findByEnabledTrue();
    }

    @Transactional(readOnly = true)
    public ChainConfig getById(UUID id) {
        return chainConfigRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", id));
    }

    @Transactional(readOnly = true)
    public ChainConfig getByIdentifier(String identifier) {
        return chainConfigRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", "identifier", identifier));
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new chain configuration entry. The identifier must be unique.
     *
     * @throws IllegalArgumentException if the identifier is already in use
     */
    public ChainConfig create(ChainConfig config) {
        chainConfigRepository.findByIdentifier(config.getIdentifier()).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "ChainConfig with identifier '" + config.getIdentifier() + "' already exists");
        });

        ChainConfig saved = chainConfigRepository.save(config);
        eventPublisher.publishEvent(new ChainAddedEvent(saved.getId(), null, null, saved.getIdentifier()));
        log.info("Created ChainConfig: id={}, identifier={}", saved.getId(), saved.getIdentifier());
        return saved;
    }

    /**
     * Applies a partial update to an existing chain configuration.
     * Only non-null fields in {@code patch} are applied.
     */
    public ChainConfig update(UUID id, ChainConfig patch) {
        ChainConfig existing = getById(id);

        if (patch.getDisplayName() != null)       existing.setDisplayName(patch.getDisplayName());
        if (patch.getRpcUrl() != null)             existing.setRpcUrl(patch.getRpcUrl());
        if (patch.getWsUrl() != null)              existing.setWsUrl(patch.getWsUrl());
        if (patch.getFallbackRpcUrls() != null)    existing.setFallbackRpcUrls(patch.getFallbackRpcUrls());
        if (patch.getBlockExplorerUrl() != null)   existing.setBlockExplorerUrl(patch.getBlockExplorerUrl());
        if (patch.getGraphNodeUrl() != null)       existing.setGraphNodeUrl(patch.getGraphNodeUrl());
        if (patch.getGraphSubgraphName() != null)  existing.setGraphSubgraphName(patch.getGraphSubgraphName());
        if (patch.getChainId() != null)            existing.setChainId(patch.getChainId());

        ChainConfig saved = chainConfigRepository.save(existing);
        eventPublisher.publishEvent(new ChainUpdatedEvent(saved.getId(), null, null));
        log.info("Updated ChainConfig: id={}, identifier={}", saved.getId(), saved.getIdentifier());
        return saved;
    }

    /** Enables a previously disabled chain configuration. */
    public void enable(UUID id) {
        ChainConfig config = getById(id);
        config.setEnabled(true);
        chainConfigRepository.save(config);
        log.info("Enabled ChainConfig: id={}, identifier={}", id, config.getIdentifier());
    }

    /** Disables a chain configuration, preventing it from being picked up by indexers. */
    public void disable(UUID id) {
        ChainConfig config = getById(id);
        config.setEnabled(false);
        chainConfigRepository.save(config);
        log.info("Disabled ChainConfig: id={}, identifier={}", id, config.getIdentifier());
    }

    /**
     * Triggers a live refresh of the {@link BlockchainClientRegistry}, re-initialising
     * Web3j and Solana RPC clients from the current enabled chain_config rows.
     */
    public void refreshBlockchainClients() {
        log.info("Refreshing blockchain client registry from chain_config...");
        eventPublisher.publishEvent(new ChainConfigUpdatedEvent(null));
        log.info("Blockchain client registry refresh complete.");
    }
}
