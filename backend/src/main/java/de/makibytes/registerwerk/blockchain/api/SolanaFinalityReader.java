package de.makibytes.registerwerk.blockchain.api;

import java.util.UUID;

/** Reads protocol-native finality for one submitted Solana transaction signature. */
public interface SolanaFinalityReader {

    enum State { PENDING, FINALIZED, FAILED }

    /**
     * A finalized result carries the immutable slot/block-hash provenance needed by the
     * compensation journal. A failed result carries the RPC transaction error for operations.
     */
    record Result(State state, Long slot, String blockHash, String failure) {
        public static Result pending() { return new Result(State.PENDING, null, null, null); }
        public static Result failed(long slot, String failure) {
            return new Result(State.FAILED, slot, null, failure);
        }
        public static Result finalized(long slot, String blockHash) {
            return new Result(State.FINALIZED, slot, blockHash, null);
        }
    }

    Result read(UUID chainConfigId, String signature);
}
