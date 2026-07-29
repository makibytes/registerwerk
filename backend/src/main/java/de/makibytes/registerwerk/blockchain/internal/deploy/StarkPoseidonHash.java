package de.makibytes.registerwerk.blockchain.internal.deploy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Starknet Poseidon hash (Hades permutation) over the STARK field, as used for all
 * v3 transaction hashes (SNIP-8) and Cairo 1 storage addressing.
 *
 * <p>Parameters are the canonical Starknet "small" instance from starkware-libs/cairo-lang
 * {@code poseidon_utils.py}: state size m = 3 (rate 2, capacity 1), 8 full + 83 partial
 * rounds, x³ S-box, and the optimized MDS matrix
 * <pre>
 *   [ 3  1  1 ]
 *   [ 1 −1  1 ]
 *   [ 1  1 −2 ]
 * </pre>
 * The 273 round constants are not magic tables but part of the spec itself:
 * {@code ark[i][j] = sha256("Hades" || (3·i + j)) mod P}. They are generated once at class
 * load exactly as the reference does, and the whole construction is pinned by
 * {@code StarkPoseidonHashTest} against independent test vectors from starknet-rs
 * (themselves generated from cairo-lang v0.11). Without a correct Poseidon hash, every
 * v3 transaction hash — and therefore every STARK ECDSA signature — is rejected by
 * the sequencer.
 */
final class StarkPoseidonHash {

    /** STARK field prime: 2^251 + 17·2^192 + 1. */
    static final BigInteger P = new BigInteger(
            "800000000000011000000000000000000000000000000000000000000000001", 16);

    private static final int FULL_ROUNDS = 8;
    private static final int PARTIAL_ROUNDS = 83;
    private static final int STATE_SIZE = 3;
    private static final int N_ROUNDS = FULL_ROUNDS + PARTIAL_ROUNDS;

    /** Add-round-key constants: ark[round][lane] = sha256("Hades" + (3·round + lane)) mod P. */
    private static final BigInteger[][] ARK = generateArk();

    private StarkPoseidonHash() {}

    private static BigInteger[][] generateArk() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            BigInteger[][] ark = new BigInteger[N_ROUNDS][STATE_SIZE];
            for (int i = 0; i < N_ROUNDS; i++) {
                for (int j = 0; j < STATE_SIZE; j++) {
                    byte[] digest = sha256.digest(
                            ("Hades" + (STATE_SIZE * i + j)).getBytes(StandardCharsets.UTF_8));
                    ark[i][j] = new BigInteger(1, digest).mod(P);
                }
            }
            return ark;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The Hades permutation on a 3-element state, in place. Full rounds cube every lane;
     * partial rounds cube only the last lane; every round adds its round key first and
     * applies the MDS mix last.
     */
    static void hadesPermutation(BigInteger[] state) {
        int round = 0;
        for (int i = 0; i < FULL_ROUNDS / 2; i++) {
            hadesRound(state, true, round++);
        }
        for (int i = 0; i < PARTIAL_ROUNDS; i++) {
            hadesRound(state, false, round++);
        }
        for (int i = 0; i < FULL_ROUNDS / 2; i++) {
            hadesRound(state, true, round++);
        }
    }

    private static void hadesRound(BigInteger[] s, boolean fullRound, int round) {
        for (int j = 0; j < STATE_SIZE; j++) {
            s[j] = s[j].add(ARK[round][j]);
        }
        if (fullRound) {
            for (int j = 0; j < STATE_SIZE; j++) {
                s[j] = s[j].modPow(BigInteger.valueOf(3), P);
            }
        } else {
            s[2] = s[2].modPow(BigInteger.valueOf(3), P);
        }
        // MDS: [3,1,1; 1,-1,1; 1,1,-2] · s
        BigInteger r0 = s[0].add(s[0]).add(s[0]).add(s[1]).add(s[2]).mod(P);
        BigInteger r1 = s[0].subtract(s[1]).add(s[2]).mod(P);
        BigInteger r2 = s[0].add(s[1]).subtract(s[2]).subtract(s[2]).mod(P);
        s[0] = r0;
        s[1] = r1;
        s[2] = r2;
    }

    /** {@code poseidon_hash(x, y)} — two-element hash: first lane of perm(x, y, 2). */
    static BigInteger hash(BigInteger x, BigInteger y) {
        BigInteger[] state = {x.mod(P), y.mod(P), BigInteger.TWO};
        hadesPermutation(state);
        return state[0];
    }

    /** {@code poseidon_hash_single(x)} — first lane of perm(x, 0, 1). */
    static BigInteger hashSingle(BigInteger x) {
        BigInteger[] state = {x.mod(P), BigInteger.ZERO, BigInteger.ONE};
        hadesPermutation(state);
        return state[0];
    }

    /**
     * {@code poseidon_hash_many}: sponge over 2-element blocks. The input is padded with a
     * single 1 followed by zeros to a multiple of the rate, so distinct-length inputs can
     * never collide.
     */
    static BigInteger hashMany(List<BigInteger> elements) {
        BigInteger[] state = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        int i = 0;
        int n = elements.size();
        while (n - i >= 2) {
            state[0] = state[0].add(elements.get(i)).mod(P);
            state[1] = state[1].add(elements.get(i + 1)).mod(P);
            hadesPermutation(state);
            i += 2;
        }
        // Padding block: [last?, 1] or [1, 0].
        if (n - i == 1) {
            state[0] = state[0].add(elements.get(i)).mod(P);
            state[1] = state[1].add(BigInteger.ONE).mod(P);
        } else {
            state[0] = state[0].add(BigInteger.ONE).mod(P);
        }
        hadesPermutation(state);
        return state[0];
    }
}
