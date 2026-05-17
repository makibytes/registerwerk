package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "registerwerk.chains")
public class EvmProperties {

    /** Outer key: chain name (e.g. "ethereum"). Inner key: network name (e.g. "mainnet", "testnet"). */
    private Map<String, Map<String, ChainNetworkProps>> chains;

    public Map<String, Map<String, ChainNetworkProps>> getChains() {
        return chains;
    }

    public void setChains(Map<String, Map<String, ChainNetworkProps>> chains) {
        this.chains = chains;
    }

    public static class ChainNetworkProps {
        private String rpcUrl;
        private long chainId;

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }

        public long getChainId() {
            return chainId;
        }

        public void setChainId(long chainId) {
            this.chainId = chainId;
        }
    }
}
