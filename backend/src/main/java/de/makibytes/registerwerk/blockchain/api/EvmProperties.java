package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "registerwerk.chains")
public class EvmProperties {

    /*
     * These are explicit instead of binding the entire registerwerk.chains subtree to a nested
     * map. That subtree also contains Solana-specific scalar properties and Canton gRPC
     * properties, neither of which can be converted to ChainNetworkProps. The previous generic
     * map was additionally declared one level too deep and therefore bound no EVM clients at all.
     */
    private Map<String, ChainNetworkProps> ethereum;
    private Map<String, ChainNetworkProps> polygon;
    private Map<String, ChainNetworkProps> base;
    private Map<String, ChainNetworkProps> fhenix;
    private Map<String, ChainNetworkProps> inco;
    private Map<String, ChainNetworkProps> arbitrum;
    private Map<String, ChainNetworkProps> avalanche;
    private Map<String, ChainNetworkProps> optimism;

    public Map<String, Map<String, ChainNetworkProps>> getChains() {
        Map<String, Map<String, ChainNetworkProps>> result = new LinkedHashMap<>();
        putIfConfigured(result, "ethereum", ethereum);
        putIfConfigured(result, "polygon", polygon);
        putIfConfigured(result, "base", base);
        putIfConfigured(result, "fhenix", fhenix);
        putIfConfigured(result, "inco", inco);
        putIfConfigured(result, "arbitrum", arbitrum);
        putIfConfigured(result, "avalanche", avalanche);
        putIfConfigured(result, "optimism", optimism);
        return result;
    }

    private static void putIfConfigured(Map<String, Map<String, ChainNetworkProps>> target,
                                        String name, Map<String, ChainNetworkProps> value) {
        if (value != null) target.put(name, value);
    }

    public Map<String, ChainNetworkProps> getEthereum() { return ethereum; }
    public void setEthereum(Map<String, ChainNetworkProps> ethereum) { this.ethereum = ethereum; }
    public Map<String, ChainNetworkProps> getPolygon() { return polygon; }
    public void setPolygon(Map<String, ChainNetworkProps> polygon) { this.polygon = polygon; }
    public Map<String, ChainNetworkProps> getBase() { return base; }
    public void setBase(Map<String, ChainNetworkProps> base) { this.base = base; }
    public Map<String, ChainNetworkProps> getFhenix() { return fhenix; }
    public void setFhenix(Map<String, ChainNetworkProps> fhenix) { this.fhenix = fhenix; }
    public Map<String, ChainNetworkProps> getInco() { return inco; }
    public void setInco(Map<String, ChainNetworkProps> inco) { this.inco = inco; }
    public Map<String, ChainNetworkProps> getArbitrum() { return arbitrum; }
    public void setArbitrum(Map<String, ChainNetworkProps> arbitrum) { this.arbitrum = arbitrum; }
    public Map<String, ChainNetworkProps> getAvalanche() { return avalanche; }
    public void setAvalanche(Map<String, ChainNetworkProps> avalanche) { this.avalanche = avalanche; }
    public Map<String, ChainNetworkProps> getOptimism() { return optimism; }
    public void setOptimism(Map<String, ChainNetworkProps> optimism) { this.optimism = optimism; }

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
