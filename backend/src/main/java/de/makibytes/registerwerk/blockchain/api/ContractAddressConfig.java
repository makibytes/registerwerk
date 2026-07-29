package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /** Shared T-REX IdentityRegistry address used by confidential ERC-3643 deployments on this
     *  chain — operator-provisioned once per chain and reused across every confidential asset,
     *  rather than bootstrapping a brand-new suite per asset (which
     *  {@code Erc3643DeploymentService} does for non-confidential ERC-3643, but confidential
     *  ERC-3643 does not yet replicate). Previously this was silently hardcoded to the zero
     *  address, deploying a "regulated" security token with no KYC gate at all. */
    private Map<String, String> confidentialIdentityRegistry = new HashMap<>();

    /** Shared confidential compliance-module address per chain identifier (optional — the
     *  contract accepts the zero address here, meaning no additional transfer-rule module). */
    private Map<String, String> confidentialCompliance = new HashMap<>();

    /** Registry operator's dedicated decrypt-only viewer address per chain identifier — passed as
     *  one of every confidential token's {@code initialViewers} at deploy (see
     *  {@code ConfidentialERC20}'s viewer-ACL note). Deliberately NOT an on-chain
     *  transaction-signing wallet; corresponds to {@code zama-relayer}'s
     *  {@code OPERATOR_DECRYPT_PRIVATE_KEY}. */
    private Map<String, String> confidentialOperatorViewer = new HashMap<>();

    /** Auditor's viewer address per chain identifier — same role as
     *  {@link #confidentialOperatorViewer} but for an independent auditor, granted at deploy so
     *  no separate post-deploy transaction is needed. */
    private Map<String, String> confidentialAuditorViewer = new HashMap<>();

    /** OrgRegistry (ecosystem org/member bindings) address per chain identifier. */
    private Map<String, String> orgRegistry = new HashMap<>();

    /** PermissionRegistry (ecosystem permission grants) address per chain identifier. */
    private Map<String, String> permissionRegistry = new HashMap<>();

    /** DappRegistry (approved marketplace dApps) address per chain identifier. */
    private Map<String, String> dappRegistry = new HashMap<>();

    /** PermissionOracle (query facade for gated dApps) address per chain identifier. */
    private Map<String, String> permissionOracle = new HashMap<>();

    /** EcosystemTrustedIssuersRegistry address per chain identifier. */
    private Map<String, String> ecosystemTir = new HashMap<>();

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

    /**
     * Returns the shared confidential-ERC-3643 T-REX IdentityRegistry address for the given
     * chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireConfidentialIdentityRegistry(String chainIdentifier) {
        return require(confidentialIdentityRegistry, chainIdentifier, "Confidential ERC-3643 IdentityRegistry");
    }

    /**
     * Returns the shared confidential-ERC-3643 compliance-module address for the given chain
     * identifier, or the zero address if none is configured (compliance is optional).
     */
    public String confidentialComplianceOrZero(String chainIdentifier) {
        String normalized = chainIdentifier.toLowerCase().replace('_', '-');
        String address = confidentialCompliance.get(normalized);
        return address == null || address.isBlank() ? "0x0000000000000000000000000000000000000000" : address;
    }

    /**
     * Returns the confidential-token initial viewer set for the given chain identifier —
     * whichever of {@link #confidentialOperatorViewer}/{@link #confidentialAuditorViewer} are
     * configured, in that order (empty entries are skipped, never returned as an empty-string
     * address). Deliberately does not throw when empty — deploying without a viewer configured
     * still produces a valid, deployable confidential token (the holder can still decrypt their
     * own balance); it just means nobody at Registerwerk can reconcile/audit it until a viewer is
     * added via {@code addViewer}, which the deploy-time log line for this asset should surface.
     */
    public List<String> confidentialInitialViewers(String chainIdentifier) {
        String normalized = chainIdentifier.toLowerCase().replace('_', '-');
        List<String> viewers = new ArrayList<>();
        addIfConfigured(viewers, confidentialOperatorViewer, normalized);
        addIfConfigured(viewers, confidentialAuditorViewer, normalized);
        return viewers;
    }

    private static void addIfConfigured(List<String> viewers, Map<String, String> map, String key) {
        String address = map.get(key);
        if (address != null && !address.isBlank()) {
            viewers.add(address);
        }
    }

    /**
     * Returns the OrgRegistry address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireOrgRegistry(String chainIdentifier) {
        return require(orgRegistry, chainIdentifier, "OrgRegistry");
    }

    /**
     * Returns the PermissionRegistry address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requirePermissionRegistry(String chainIdentifier) {
        return require(permissionRegistry, chainIdentifier, "PermissionRegistry");
    }

    /**
     * Returns the DappRegistry address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireDappRegistry(String chainIdentifier) {
        return require(dappRegistry, chainIdentifier, "DappRegistry");
    }

    /**
     * Returns the PermissionOracle address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requirePermissionOracle(String chainIdentifier) {
        return require(permissionOracle, chainIdentifier, "PermissionOracle");
    }

    /**
     * Returns the EcosystemTrustedIssuersRegistry address for the given chain identifier.
     *
     * @throws IllegalStateException if no address is configured for the identifier
     */
    public String requireEcosystemTir(String chainIdentifier) {
        return require(ecosystemTir, chainIdentifier, "EcosystemTrustedIssuersRegistry");
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

    public Map<String, String> getConfidentialIdentityRegistry() { return confidentialIdentityRegistry; }
    public void setConfidentialIdentityRegistry(Map<String, String> m) { this.confidentialIdentityRegistry = m; }

    public Map<String, String> getConfidentialCompliance() { return confidentialCompliance; }
    public void setConfidentialCompliance(Map<String, String> m) { this.confidentialCompliance = m; }

    public Map<String, String> getConfidentialOperatorViewer() { return confidentialOperatorViewer; }
    public void setConfidentialOperatorViewer(Map<String, String> m) { this.confidentialOperatorViewer = m; }

    public Map<String, String> getConfidentialAuditorViewer() { return confidentialAuditorViewer; }
    public void setConfidentialAuditorViewer(Map<String, String> m) { this.confidentialAuditorViewer = m; }

    public Map<String, String> getOrgRegistry() { return orgRegistry; }
    public void setOrgRegistry(Map<String, String> m) { this.orgRegistry = m; }

    public Map<String, String> getPermissionRegistry() { return permissionRegistry; }
    public void setPermissionRegistry(Map<String, String> m) { this.permissionRegistry = m; }

    public Map<String, String> getDappRegistry() { return dappRegistry; }
    public void setDappRegistry(Map<String, String> m) { this.dappRegistry = m; }

    public Map<String, String> getPermissionOracle() { return permissionOracle; }
    public void setPermissionOracle(Map<String, String> m) { this.permissionOracle = m; }

    public Map<String, String> getEcosystemTir() { return ecosystemTir; }
    public void setEcosystemTir(Map<String, String> m) { this.ecosystemTir = m; }
}
