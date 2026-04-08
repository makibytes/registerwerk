package de.makibytes.registerwerk.application.blockchain;

import de.makibytes.registerwerk.domain.chain.ChainConfig;
import de.makibytes.registerwerk.infrastructure.blockchain.evm.Web3jClientFactory;
import de.makibytes.registerwerk.infrastructure.blockchain.solana.SolanaClientFactory;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.ChainConfigRepository;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry mapping {@link ChainDescriptor}s to their respective RPC clients.
 *
 * <p>The static client set is populated by {@link de.makibytes.registerwerk.config.BlockchainConfig}
 * at startup from application-property-based configuration. The dynamic set is refreshed at
 * runtime from the {@code chain_config} database table via {@link #refresh()}.
 *
 * <p>Lookups check both sets, with dynamic (DB-sourced) entries taking precedence.
 */
@Component
public class BlockchainClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(BlockchainClientRegistry.class);

    // ── Static clients (from BlockchainConfig / application properties) ───────

    private final Map<ChainDescriptor, Web3j>    staticEvmClients;
    private final Map<ChainDescriptor, RpcClient> staticSolanaClients;

    // ── Dynamic clients (from chain_config table) ─────────────────────────────

    /** identifier → Web3j, populated by {@link #refresh()}. */
    private volatile Map<String, Web3j>    dynamicEvmClients    = new ConcurrentHashMap<>();
    /** identifier → RpcClient, populated by {@link #refresh()}. */
    private volatile Map<String, RpcClient> dynamicSolanaClients = new ConcurrentHashMap<>();

    // ── Optional dependencies injected lazily to avoid circular beans ─────────

    @Autowired(required = false)
    private ChainConfigRepository chainConfigRepository;

    @Autowired(required = false)
    private Web3jClientFactory web3jClientFactory;

    @Autowired(required = false)
    private SolanaClientFactory solanaClientFactory;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Primary constructor invoked by {@link de.makibytes.registerwerk.config.BlockchainConfig}.
     */
    public BlockchainClientRegistry(
            Map<ChainDescriptor, Web3j> evmClients,
            Map<ChainDescriptor, RpcClient> solanaClients) {
        this.staticEvmClients    = Collections.unmodifiableMap(new HashMap<>(evmClients));
        this.staticSolanaClients = Collections.unmodifiableMap(new HashMap<>(solanaClients));
    }

    // ── Lookups — ChainDescriptor-based (legacy) ──────────────────────────────

    /**
     * Returns the Web3j client for the given EVM chain descriptor.
     *
     * @throws IllegalArgumentException if no client is configured for the descriptor
     */
    public Web3j getEvmClient(ChainDescriptor descriptor) {
        Web3j client = staticEvmClients.get(descriptor);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No EVM client configured for chain descriptor: " + descriptor);
        }
        return client;
    }

    /**
     * Returns the Solana RpcClient for the given descriptor.
     *
     * @throws IllegalArgumentException if no client is configured for the descriptor
     */
    public RpcClient getSolanaClient(ChainDescriptor descriptor) {
        RpcClient client = staticSolanaClients.get(descriptor);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No Solana client configured for chain descriptor: " + descriptor);
        }
        return client;
    }

    // ── Lookups — identifier-based (dynamic, from chain_config) ──────────────

    /**
     * Returns the Web3j client for a chain identified by its stable string identifier
     * (e.g. {@code "ETHEREUM_MAINNET"}).
     *
     * <p>Dynamic clients (loaded via {@link #refresh()}) take precedence over static ones.
     *
     * @throws IllegalArgumentException if no client is registered for the identifier
     */
    public Web3j getEvmClientByIdentifier(String identifier) {
        Web3j dynamic = dynamicEvmClients.get(identifier);
        if (dynamic != null) {
            return dynamic;
        }
        // Fall back to searching the static map by inspecting all descriptors.
        for (Map.Entry<ChainDescriptor, Web3j> entry : staticEvmClients.entrySet()) {
            String key = entry.getKey().chain().name() + "_" + entry.getKey().network().name();
            if (key.equalsIgnoreCase(identifier)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException(
                "No EVM client configured for identifier: " + identifier);
    }

    /**
     * Returns the Solana RpcClient for a chain identified by its stable string identifier.
     *
     * @throws IllegalArgumentException if no client is registered for the identifier
     */
    public RpcClient getSolanaClientByIdentifier(String identifier) {
        RpcClient dynamic = dynamicSolanaClients.get(identifier);
        if (dynamic != null) {
            return dynamic;
        }
        for (Map.Entry<ChainDescriptor, RpcClient> entry : staticSolanaClients.entrySet()) {
            String key = entry.getKey().chain().name() + "_" + entry.getKey().network().name();
            if (key.equalsIgnoreCase(identifier)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException(
                "No Solana client configured for identifier: " + identifier);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Reloads Web3j and Solana RPC client instances from all enabled {@code chain_config} rows.
     *
     * <p>Previous dynamic clients are replaced atomically. Static clients (from
     * application properties) are not affected.
     *
     * <p>No-ops if the optional {@link ChainConfigRepository} or factory beans are not wired.
     */
    public void refresh() {
        if (chainConfigRepository == null) {
            log.warn("ChainConfigRepository not available; skipping dynamic client refresh.");
            return;
        }

        List<ChainConfig> enabledChains = chainConfigRepository.findByEnabledTrue();
        Map<String, Web3j>    newEvmClients    = new ConcurrentHashMap<>();
        Map<String, RpcClient> newSolanaClients = new ConcurrentHashMap<>();

        for (ChainConfig chain : enabledChains) {
            try {
                if (chain.getChainType() == ChainConfig.ChainType.EVM) {
                    if (web3jClientFactory != null && chain.getRpcUrl() != null) {
                        Web3j client = web3jClientFactory.createClient(chain.getRpcUrl());
                        newEvmClients.put(chain.getIdentifier(), client);
                        log.debug("Refreshed EVM client for identifier={}", chain.getIdentifier());
                    }
                } else if (chain.getChainType() == ChainConfig.ChainType.SOLANA) {
                    if (solanaClientFactory != null && chain.getRpcUrl() != null) {
                        RpcClient client = solanaClientFactory.createClient(chain.getRpcUrl());
                        newSolanaClients.put(chain.getIdentifier(), client);
                        log.debug("Refreshed Solana client for identifier={}", chain.getIdentifier());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to refresh client for chain {}: {}", chain.getIdentifier(),
                        e.getMessage(), e);
            }
        }

        this.dynamicEvmClients    = newEvmClients;
        this.dynamicSolanaClients = newSolanaClients;

        log.info("BlockchainClientRegistry refresh complete: {} EVM, {} Solana dynamic clients.",
                newEvmClients.size(), newSolanaClients.size());
    }

    // ── Read-only accessors (static maps) ─────────────────────────────────────

    public Map<ChainDescriptor, Web3j> getEvmClients() {
        return staticEvmClients;
    }

    public Map<ChainDescriptor, RpcClient> getSolanaClients() {
        return staticSolanaClients;
    }
}
