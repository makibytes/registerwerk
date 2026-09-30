package de.makibytes.registerwerk.chain.api;

/**
 * Port (implemented in {@code blockchain.internal}, which owns the RPC clients) that checks whether
 * an RPC endpoint really serves the chain a {@link ChainConfig} pins (P4C-1): {@code eth_chainId}
 * against {@code chain_config.chain_id}, and the genesis block hash against
 * {@code chain_config.genesis_hash} (Solana: {@code getGenesisHash}). The first successful check
 * with a matching chain id captures the genesis hash when none is pinned yet.
 */
public interface RpcNodeChainVerifier {

    enum Outcome {
        /** The endpoint answered and agrees with the pin. */
        MATCH,
        /** The endpoint answered with a different chain id or genesis hash. */
        MISMATCH,
        /** Could not verify (unreachable, no pin to compare with, chain type without a check). */
        UNVERIFIABLE
    }

    record Verdict(Outcome outcome, String detail) {
        public static Verdict unverifiable(String detail) { return new Verdict(Outcome.UNVERIFIABLE, detail); }
    }

    Verdict verify(ChainConfig chain, String url);
}
