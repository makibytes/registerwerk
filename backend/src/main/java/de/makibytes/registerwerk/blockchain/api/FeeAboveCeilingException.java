package de.makibytes.registerwerk.blockchain.api;

import java.math.BigInteger;

/**
 * The fee (or gas limit) that would be signed exceeds the applicable ceiling: the per-chain
 * override in {@code chain_config} or, when unset, the global default cap. Nothing is signed.
 */
public class FeeAboveCeilingException extends IllegalStateException {

    private final String kind;
    private final BigInteger value;
    private final BigInteger ceiling;

    public FeeAboveCeilingException(String kind, BigInteger value, BigInteger ceiling) {
        super("Refusing to sign: " + kind + " " + value + " exceeds the ceiling " + ceiling
                + " (chain_config override or registerwerk.blockchain.fee-cap defaults)");
        this.kind = kind;
        this.value = value;
        this.ceiling = ceiling;
    }

    public String getKind() { return kind; }
    public BigInteger getValue() { return value; }
    public BigInteger getCeiling() { return ceiling; }
}
