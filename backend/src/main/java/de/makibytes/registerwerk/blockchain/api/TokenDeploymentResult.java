package de.makibytes.registerwerk.blockchain.api;

/**
 * Outcome of a {@link TokenDeploymentPort#deploy} call.
 *
 * <p>{@code contractAddress} is populated only when the target chain lets the deployed address
 * be known at submission time — e.g. Starknet's UDC precomputation, or a Stellar issuer account,
 * both known before the transaction is even broadcast. For chains where the address is only
 * known once the transaction is mined (EVM), it is left {@code null} here and filled in later via
 * {@code AssetDeploymentService.syncFromChain} / {@code confirmDeployment}.
 */
public record TokenDeploymentResult(String txHash, String contractAddress) {

    /** For chains that only report a tx hash at submission time (contract address unknown yet). */
    public static TokenDeploymentResult txOnly(String txHash) {
        return new TokenDeploymentResult(txHash, null);
    }
}
