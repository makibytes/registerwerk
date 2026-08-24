package de.makibytes.registerwerk.blockchain.api;

/**
 * Outcome of a {@link TokenDeploymentPort#deploy} call.
 *
 * <p>{@code contractAddress} is the target chain's durable asset identifier when one is available:
 * an EVM/Cairo contract, a Solana mint, or a Stellar issuer. It may be known before finality
 * (Solana key generation and Starknet UDC precomputation), so callers must never interpret its
 * presence as confirmation; {@code AssetDeploymentService} owns the chain-specific finality gate.
 */
public record TokenDeploymentResult(String txHash, String contractAddress) {

    /** For chains that only report a tx hash at submission time (contract address unknown yet). */
    public static TokenDeploymentResult txOnly(String txHash) {
        return new TokenDeploymentResult(txHash, null);
    }
}
