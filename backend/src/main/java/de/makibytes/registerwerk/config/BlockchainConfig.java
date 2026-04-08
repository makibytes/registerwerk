package de.makibytes.registerwerk.config;

import de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry;
import de.makibytes.registerwerk.application.blockchain.ChainDescriptor;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import de.makibytes.registerwerk.infrastructure.blockchain.evm.EvmProperties;
import de.makibytes.registerwerk.infrastructure.blockchain.evm.Web3jClientFactory;
import de.makibytes.registerwerk.infrastructure.blockchain.solana.SolanaClientFactory;
import de.makibytes.registerwerk.infrastructure.blockchain.solana.SolanaProperties;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class BlockchainConfig {

    private static final Logger log = LoggerFactory.getLogger(BlockchainConfig.class);

    private final EvmProperties evmProperties;
    private final SolanaProperties solanaProperties;
    private final Web3jClientFactory web3jClientFactory;
    private final SolanaClientFactory solanaClientFactory;

    public BlockchainConfig(
            EvmProperties evmProperties,
            SolanaProperties solanaProperties,
            Web3jClientFactory web3jClientFactory,
            SolanaClientFactory solanaClientFactory) {
        this.evmProperties = evmProperties;
        this.solanaProperties = solanaProperties;
        this.web3jClientFactory = web3jClientFactory;
        this.solanaClientFactory = solanaClientFactory;
    }

    @Bean
    public BlockchainClientRegistry blockchainClientRegistry() {
        Map<ChainDescriptor, Web3j> evmClients = new HashMap<>();
        Map<ChainDescriptor, RpcClient> solanaClients = new HashMap<>();

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

        return new BlockchainClientRegistry(evmClients, solanaClients);
    }
}
