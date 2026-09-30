package de.makibytes.registerwerk.blockchain.api;

/**
 * {@code eth_estimateGas} showed that the call would revert. Thrown before anything is signed, so
 * no nonce is consumed and no doomed transaction is broadcast with a fallback gas limit.
 */
public class ChainRevertException extends IllegalStateException {

    private final String revertReason;

    public ChainRevertException(String revertReason) {
        super("Transaction would revert on-chain: " + revertReason);
        this.revertReason = revertReason;
    }

    public String getRevertReason() {
        return revertReason;
    }
}
