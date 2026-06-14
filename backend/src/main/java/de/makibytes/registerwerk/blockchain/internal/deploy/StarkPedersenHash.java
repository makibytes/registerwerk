package de.makibytes.registerwerk.blockchain.internal.deploy;

import java.math.BigInteger;
import java.util.List;

/**
 * Starkware Pedersen hash over the STARK curve, as used for Starknet transaction
 * hashes ({@code hash_on_elements}) and storage addressing.
 *
 * <p>Implements the optimized 4-fixed-point variant equivalent to the canonical
 * bit-by-bit definition:
 * <pre>
 *   pedersen(a, b) = (SHIFT + a_low·P0 + a_high·P1 + b_low·P2 + b_high·P3).x
 * </pre>
 * where {@code low} is the lower 248 bits and {@code high} the upper 4 bits of
 * each 252-bit field element.
 *
 * <p>The constant points are taken verbatim from starkware-libs/cairo-lang
 * {@code pedersen_params.json} (CONSTANT_POINTS[0], [2], [250], [254], [502]) and
 * the implementation is pinned by {@code StarkPedersenHashTest} against the
 * official test vector from the Starkware crypto test suite. Without a correct
 * Pedersen hash, every Invoke transaction hash — and therefore every STARK ECDSA
 * signature — is rejected by the sequencer.
 */
final class StarkPedersenHash {

    /** STARK field prime: 2^251 + 17·2^192 + 1. */
    static final BigInteger P = new BigInteger(
            "800000000000011000000000000000000000000000000000000000000000001", 16);

    private static final BigInteger LOW_MASK = BigInteger.ONE.shiftLeft(248).subtract(BigInteger.ONE);

    // CONSTANT_POINTS[0] — the shift point.
    private static final BigInteger[] SHIFT = {
            new BigInteger("49ee3eba8c1600700ee1b87eb599f16716b0b1022947733551fde4050ca6804", 16),
            new BigInteger("3ca0cfe4b3bc6ddf346d49d06ea0ed34e621062c0e056c1d0405d266e10268a", 16)};

    // CONSTANT_POINTS[2] — multiplies the low 248 bits of the first element.
    private static final BigInteger[] P0 = {
            new BigInteger("234287dcbaffe7f969c748655fca9e58fa8120b6d56eb0c1080d17957ebe47b", 16),
            new BigInteger("3b056f100f96fb21e889527d41f4e39940135dd7a6c94cc6ed0268ee89e5615", 16)};

    // CONSTANT_POINTS[250] — multiplies the high 4 bits of the first element.
    private static final BigInteger[] P1 = {
            new BigInteger("4fa56f376c83db33f9dab2656558f3399099ec1de5e3018b7a6932dba8aa378", 16),
            new BigInteger("3fa0984c931c9e38113e0c0e47e4401562761f92a7a23b45168f4e80ff5b54d", 16)};

    // CONSTANT_POINTS[254] — multiplies the low 248 bits of the second element.
    private static final BigInteger[] P2 = {
            new BigInteger("4ba4cc166be8dec764910f75b45f74b40c690c74709e90f3aa372f0bd2d6997", 16),
            new BigInteger("40301cf5c1751f4b971e46c4ede85fcac5c59a5ce5ae7c48151f27b24b219c", 16)};

    // CONSTANT_POINTS[502] — multiplies the high 4 bits of the second element.
    private static final BigInteger[] P3 = {
            new BigInteger("54302dcb0e6cc1c6e44cca8f61a63bb2ca65048d53fb325d36ff12c49a58202", 16),
            new BigInteger("1b77b3e37d13504b348046268d8ae25ce98ad783c25561a879dcc77e99c2426", 16)};

    private StarkPedersenHash() {}

    /** Pedersen hash of two field elements. */
    static BigInteger pedersen(BigInteger a, BigInteger b) {
        BigInteger[] pt = SHIFT;
        pt = addComponent(pt, a, P0, P1);
        pt = addComponent(pt, b, P2, P3);
        return pt[0];
    }

    /**
     * Starknet {@code hash_on_elements}:
     * {@code h(...h(h(0, e1), e2)..., en), n)} — a Pedersen chain seeded with 0,
     * finalized by hashing in the element count.
     */
    static BigInteger hashOnElements(List<BigInteger> elements) {
        BigInteger h = BigInteger.ZERO;
        for (BigInteger e : elements) {
            h = pedersen(h, e);
        }
        return pedersen(h, BigInteger.valueOf(elements.size()));
    }

    private static BigInteger[] addComponent(BigInteger[] pt, BigInteger x,
                                             BigInteger[] lowPoint, BigInteger[] highPoint) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) {
            throw new IllegalArgumentException("Pedersen input out of field range: " + x.toString(16));
        }
        BigInteger low = x.and(LOW_MASK);
        BigInteger high = x.shiftRight(248);
        if (low.signum() > 0) {
            pt = StarknetTokenService.ecAdd(pt, StarknetTokenService.ecMul(low, lowPoint));
        }
        if (high.signum() > 0) {
            pt = StarknetTokenService.ecAdd(pt, StarknetTokenService.ecMul(high, highPoint));
        }
        return pt;
    }
}
