package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.chains.solana")
public class SolanaProperties {

    private NetworkProps mainnet;
    private NetworkProps testnet;

    public NetworkProps getMainnet() {
        return mainnet;
    }

    public void setMainnet(NetworkProps mainnet) {
        this.mainnet = mainnet;
    }

    public NetworkProps getTestnet() {
        return testnet;
    }

    public void setTestnet(NetworkProps testnet) {
        this.testnet = testnet;
    }

    public static class NetworkProps {
        private String rpcUrl;

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }
    }
}
