package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.CantonClientProvider;
import de.makibytes.registerwerk.chain.api.CantonLedgerEndpoint;
import de.makibytes.registerwerk.chain.api.CantonProperties;
import de.makibytes.registerwerk.blockchain.api.EvmProperties;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.blockchain.api.SolanaProperties;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class BlockchainConfig {

    private static final Logger log = LoggerFactory.getLogger(BlockchainConfig.class);

    private final EvmProperties evmProperties;
    private final SolanaProperties solanaProperties;
    private final CantonProperties cantonProperties;
    private final Web3jClientFactory web3jClientFactory;
    private final SolanaClientFactory solanaClientFactory;
    private final CantonClientProvider cantonClientFactory;

    @Autowired
    public BlockchainConfig(
            EvmProperties evmProperties,
            SolanaProperties solanaProperties,
            CantonProperties cantonProperties,
            Web3jClientFactory web3jClientFactory,
            SolanaClientFactory solanaClientFactory,
            @Nullable CantonClientProvider cantonClientFactory) {
        this.evmProperties = evmProperties;
        this.solanaProperties = solanaProperties;
        this.cantonProperties = cantonProperties;
        this.web3jClientFactory = web3jClientFactory;
        this.solanaClientFactory = solanaClientFactory;
        this.cantonClientFactory = cantonClientFactory;
    }

    @Bean
    public BlockchainClientRegistry blockchainClientRegistry() {
        Map<ChainDescriptor, Web3j>              evmClients    = new HashMap<>();
        Map<ChainDescriptor, RpcClient>          solanaClients = new HashMap<>();
        Map<ChainDescriptor, CantonLedgerEndpoint> cantonClients = new HashMap<>();

        if (evmProperties.getChains() != null) {
            evmProperties.getChains().forEach((chainName, networkMap) -> {
                Chain chain;
                try {
                    chain = Chain.valueOf(chainName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping unknown chain: {}", chainName);
                    return;
                }

                if (networkMap != null) {
                    networkMap.forEach((networkName, props) -> {
                        if (props == null || props.getRpcUrl() == null) return;
                        Network network;
                        try {
                            network = Network.valueOf(networkName.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            log.warn("Skipping unknown network: {}", networkName);
                            return;
                        }
                        ChainDescriptor descriptor = new ChainDescriptor(chain, network);
                        Web3j client = web3jClientFactory.createClient(props.getRpcUrl());
                        evmClients.put(descriptor, client);
                        log.info("Registered EVM client for {}", descriptor);
                    });
                }
            });
        }

        // Solana mainnet
        if (solanaProperties.getMainnet() != null && solanaProperties.getMainnet().getRpcUrl() != null) {
            ChainDescriptor mainnetDescriptor = new ChainDescriptor(Chain.SOLANA, Network.MAINNET);
            solanaClients.put(mainnetDescriptor,
                solanaClientFactory.createClient(solanaProperties.getMainnet().getRpcUrl()));
            log.info("Registered Solana client for {}", mainnetDescriptor);
        }

        // Solana testnet
        if (solanaProperties.getTestnet() != null && solanaProperties.getTestnet().getRpcUrl() != null) {
            ChainDescriptor testnetDescriptor = new ChainDescriptor(Chain.SOLANA, Network.TESTNET);
            solanaClients.put(testnetDescriptor,
                solanaClientFactory.createClient(solanaProperties.getTestnet().getRpcUrl()));
            log.info("Registered Solana client for {}", testnetDescriptor);
        }

        if (cantonClientFactory != null) {
            // Canton mainnet
            registerCantonClient(cantonClients, cantonProperties.getMainnet(), Chain.CANTON, Network.MAINNET);
            // Canton devnet (TESTNET network maps to Canton DevNet)
            registerCantonClient(cantonClients, cantonProperties.getDevnet(), Chain.CANTON, Network.TESTNET);
        }

        return new BlockchainClientRegistry(evmClients, solanaClients, cantonClients,
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    private void registerCantonClient(
            Map<ChainDescriptor, CantonLedgerEndpoint> clients,
            CantonProperties.NetworkProps props,
            Chain chain, Network network) {
        if (props == null || !StringUtils.hasText(props.getLedgerApiUrl())) return;
        try {
            ChainDescriptor descriptor = new ChainDescriptor(chain, network);
            clients.put(descriptor, cantonClientFactory.createClient(
                    props.getLedgerApiUrl(),
                    props.getSynchronizerId(),
                    props.getApplicationId(),
                    props.getAuthToken()));
            log.info("Registered Canton client for {}", descriptor);
        } catch (Exception e) {
            log.warn("Failed to connect Canton client for {} {}: {}", chain, network, e.getMessage());
        }
    }
}
