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

    /**
     * A transaction that is still un-mined (no receipt) after this many seconds is marked
     * TIMEOUT. A mined-but-not-yet-confirmed transaction is never timed out — it stays
     * pending until it reaches the confirmation depth.
     */
    private long timeoutSeconds = 900;

    public int confirmationsFor(String chain) {
        if (chain == null) {
            return defaultConfirmations;
        }
        return confirmationsByChain.getOrDefault(chain.toUpperCase(Locale.ROOT), defaultConfirmations);
    }

    public int getDefaultConfirmations() { return defaultConfirmations; }
    public void setDefaultConfirmations(int defaultConfirmations) { this.defaultConfirmations = defaultConfirmations; }

    public Map<String, Integer> getConfirmationsByChain() { return confirmationsByChain; }
    public void setConfirmationsByChain(Map<String, Integer> confirmationsByChain) {
        // Normalise keys to upper-case so lookups are case-insensitive regardless of yaml casing.
        Map<String, Integer> normalised = new HashMap<>();
        confirmationsByChain.forEach((k, v) -> normalised.put(k.toUpperCase(Locale.ROOT), v));
        this.confirmationsByChain = normalised;
    }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
