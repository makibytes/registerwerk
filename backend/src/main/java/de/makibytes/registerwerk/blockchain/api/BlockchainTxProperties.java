package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finality policy for on-chain transactions. A transaction is only marked SUCCESS/FAILED
 * once its receipt is buried under {@code confirmations} blocks, so a shallow reorg that
 * drops or replaces the transaction cannot leave the register asserting a state the chain
 * later abandons.
 *
 * <p>Confirmation depth is chain-specific — probabilistic-finality chains and those with a
 * history of reorgs need more. Configure per chain (key = {@code de.makibytes...chain.api.Chain}
 * name, e.g. {@code POLYGON}); anything unset uses {@code defaultConfirmations}.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.blockchain.tx")
public class BlockchainTxProperties {

    /** Applied to any chain without an explicit override. */
    private int defaultConfirmations = 12;

    /** Per-chain confirmation depth, keyed by Chain enum name (case-insensitive). */
    private Map<String, Integer> confirmationsByChain = new HashMap<>();

    /** SAFE-level confirmation depth for {@code DEPTH_BASED} chains, applied to any chain without
     *  an explicit override. Ethereum's real {@code safe} tag lands at ~1 epoch vs {@code
     *  finalized}'s ~2, so a quarter of the FINALIZED depth is the default here — deliberately
     *  conservative rather than tuned per chain, since a DEPTH_BASED chain has no real safe/
     *  finalized distinction to begin with (see {@code ChainConfig.FinalityModel}'s javadoc);
     *  {@code TAG_BASED} chains ignore this and read the node's actual {@code safe} tag instead. */
    private int defaultSafeConfirmations = 3;

    /** Per-chain SAFE-level confirmation depth, keyed by Chain enum name (case-insensitive). */
    private Map<String, Integer> safeConfirmationsByChain = new HashMap<>();

    /**
     * A transaction that is still un-mined (no receipt) after this many seconds is marked
     * TIMEOUT. A mined-but-not-yet-confirmed transaction is never timed out — it stays
     * pending until it reaches the confirmation depth.
     */
    private long timeoutSeconds = 900;

    /**
     * How long after being marked TIMEOUT a transaction is still polled for a late receipt (P4B-5).
     * TIMEOUT is "not yet mined", not "failed": a transaction stuck behind a low fee or a nonce gap
     * can be mined days later, so it is reconciled instead of forgotten. Default 7 days.
     */
    private long lateMinedWindowSeconds = 7 * 24 * 3600L;

    /**
     * A TIMEOUT transaction whose outbox nonce is proven consumed by a different transaction is
     * only declared REPLACED after the chain has reported the nonce as used for this long, so a
     * shallow reorg or a lagging node cannot turn a live transaction into a "confirmed failure".
     */
    private long replacedConfirmationSeconds = 600;

    /**
     * Function-name fragments (case-insensitive "contains") of registry-mutating transactions that need a
     * second-source receipt confirmation before completion (P4C-6). {@code *} requires it for every
     * transaction. Finality depth itself always comes from {@code chain_config} / this class, never from
     * a node's or chaincache's self-declared capabilities.
     */
    private java.util.List<String> secondSourceMethods = java.util.List.of(
            "forced", "forceburn", "burn", "mint", "freeze", "pause", "recovery", "claim", "identity",
            "whitelist", "trustedissuer", "ownership", "grantrole", "revokerole", "compliance", "anchor");

    public boolean requiresSecondSource(String methodName) {
        if (methodName == null) return false;
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String fragment : secondSourceMethods) {
            if (fragment.equals("*") || lower.contains(fragment.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public java.util.List<String> getSecondSourceMethods() { return secondSourceMethods; }
    public void setSecondSourceMethods(java.util.List<String> secondSourceMethods) {
        this.secondSourceMethods = java.util.List.copyOf(secondSourceMethods);
    }

    public int confirmationsFor(String chain) {
        if (chain == null) {
            return defaultConfirmations;
        }
        return confirmationsByChain.getOrDefault(chain.toUpperCase(Locale.ROOT), defaultConfirmations);
    }

    /** @return the chain's SAFE-level confirmation depth, clamped to never exceed its FINALIZED
     *  depth — a misconfigured override (safe &gt; finalized) would otherwise let a block report
     *  SAFE without ever having been able to report FINALIZED first, which is not a real ordering
     *  under {@link de.makibytes.registerwerk.blockchain.api.EvmUtils#finalityOf}. */
    public int safeConfirmationsFor(String chain) {
        int safe = chain == null
                ? defaultSafeConfirmations
                : safeConfirmationsByChain.getOrDefault(chain.toUpperCase(Locale.ROOT), defaultSafeConfirmations);
        return Math.min(safe, confirmationsFor(chain));
    }

    public long getLateMinedWindowSeconds() { return lateMinedWindowSeconds; }
    public void setLateMinedWindowSeconds(long lateMinedWindowSeconds) { this.lateMinedWindowSeconds = lateMinedWindowSeconds; }

    public long getReplacedConfirmationSeconds() { return replacedConfirmationSeconds; }
    public void setReplacedConfirmationSeconds(long replacedConfirmationSeconds) { this.replacedConfirmationSeconds = replacedConfirmationSeconds; }

    public int getDefaultConfirmations() { return defaultConfirmations; }
    public void setDefaultConfirmations(int defaultConfirmations) { this.defaultConfirmations = defaultConfirmations; }

    public Map<String, Integer> getConfirmationsByChain() { return confirmationsByChain; }
    public void setConfirmationsByChain(Map<String, Integer> confirmationsByChain) {
        // Normalise keys to upper-case so lookups are case-insensitive regardless of yaml casing.
        Map<String, Integer> normalised = new HashMap<>();
        confirmationsByChain.forEach((k, v) -> normalised.put(k.toUpperCase(Locale.ROOT), v));
        this.confirmationsByChain = normalised;
    }

    public int getDefaultSafeConfirmations() { return defaultSafeConfirmations; }
    public void setDefaultSafeConfirmations(int defaultSafeConfirmations) { this.defaultSafeConfirmations = defaultSafeConfirmations; }

    public Map<String, Integer> getSafeConfirmationsByChain() { return safeConfirmationsByChain; }
    public void setSafeConfirmationsByChain(Map<String, Integer> safeConfirmationsByChain) {
        Map<String, Integer> normalised = new HashMap<>();
        safeConfirmationsByChain.forEach((k, v) -> normalised.put(k.toUpperCase(Locale.ROOT), v));
        this.safeConfirmationsByChain = normalised;
    }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
