package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds {@code registerwerk.contracts.*} properties to Java objects.
 *
 * <p>Each map key is a chain identifier (e.g. {@code ethereum-testnet}) and the value is the
 * deployed contract address. These addresses are populated by {@code forge script Deploy} and
 * stored in environment variables or a secrets manager.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.contracts")
public class ContractAddressConfig {

    /** AssetTokenFactory address per chain identifier. */
    private Map<String, String> assetTokenFactory = new HashMap<>();

    /** EwpgTREXFactory address per chain identifier. */
    private Map<String, String> trexFactory = new HashMap<>();

    /** ONCHAINID IdFactory address per chain identifier. */
    private Map<String, String> idFactory = new HashMap<>();

    /** EwpgConfidentialFactory (Zama fhEVM) address per chain identifier. */
    private Map<String, String> confidentialFactory = new HashMap<>();

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the AssetTokenFactory address for the given chain identifier,
     * normalising hyphen/underscore variants.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireAssetTokenFactory(String chainIdentifier) {
        return require(assetTokenFactory, chainIdentifier, "AssetTokenFactory");
    }

    /**
     * Returns the EwpgTREXFactory address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireTrexFactory(String chainIdentifier) {
        return require(trexFactory, chainIdentifier, "EwpgTREXFactory");
    }

    /**
     * Returns the ONCHAINID IdFactory address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireIdFactory(String chainIdentifier) {
        return require(idFactory, chainIdentifier, "IdFactory");
    }

    /**
     * Returns the EwpgConfidentialFactory (Zama fhEVM) address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireConfidentialFactory(String chainIdentifier) {
        return require(confidentialFactory, chainIdentifier, "EwpgConfidentialFactory");
    }

    private String require(Map<String, String> map, String key, String contractName) {
        // Normalise: "ETHEREUM_TESTNET" → "ethereum-testnet"
        String normalized = key.toLowerCase().replace('_', '-');
        String address = map.get(normalized);
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    contractName + " address not configured for chain: " + key
                    + ". Set the corresponding environment variable (e.g. TREX_FACTORY_ETH_TESTNET).");
        }
        return address;
    }

    // ── Getters / Setters (required by @ConfigurationProperties) ─────────────

    public Map<String, String> getAssetTokenFactory() { return assetTokenFactory; }
    public void setAssetTokenFactory(Map<String, String> m) { this.assetTokenFactory = m; }

    public Map<String, String> getTrexFactory() { return trexFactory; }
    public void setTrexFactory(Map<String, String> m) { this.trexFactory = m; }

    public Map<String, String> getIdFactory() { return idFactory; }
    public void setIdFactory(Map<String, String> m) { this.idFactory = m; }

    public Map<String, String> getConfidentialFactory() { return confidentialFactory; }
    public void setConfidentialFactory(Map<String, String> m) { this.confidentialFactory = m; }
}
