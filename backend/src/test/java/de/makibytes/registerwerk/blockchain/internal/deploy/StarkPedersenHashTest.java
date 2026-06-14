package de.makibytes.registerwerk.blockchain.internal.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Pedersen implementation against the official Starkware test vector
 * (cairo-lang crypto test suite) and against vectors cross-validated with the
 * canonical bit-by-bit definition over the full 506-point table from
 * pedersen_params.json. If any of these fail, every Starknet transaction hash
 * — and therefore every signature — produced by StarknetTokenService is invalid.
 */
@DisplayName("StarkPedersenHash official-vector unit tests")
class StarkPedersenHashTest {

    @Test
    @DisplayName("matches the official Starkware pedersen test vector")
    void officialVector() {
        BigInteger a = new BigInteger(
                "3d937c035c878245caf64531a5756109c53068da139362728feb561405371cb", 16);
        BigInteger b = new BigInteger(
                "208a0a10250e382e1e4bbe2880906c2791bf6275695e02fbbc6aeff9cd8b31a", 16);
        BigInteger expected = new BigInteger(
                "30e480bed5fe53fa909cc0f8c4d99b8f9f2c016be4c41e13a4848797979c662", 16);

        assertThat(StarkPedersenHash.pedersen(a, b)).isEqualTo(expected);
    }

    @Test
    @DisplayName("pedersen(0,0) equals the shift point x-coordinate")
    void zeroZero() {
        assertThat(StarkPedersenHash.pedersen(BigInteger.ZERO, BigInteger.ZERO))
                .isEqualTo(new BigInteger(
                        "49ee3eba8c1600700ee1b87eb599f16716b0b1022947733551fde4050ca6804", 16));
    }

    @Test
    @DisplayName("hash_on_elements([1,2,3,4]) matches the cross-validated vector")
    void hashOnElementsVector() {
        List<BigInteger> elements = List.of(
                BigInteger.ONE, BigInteger.TWO,
                BigInteger.valueOf(3), BigInteger.valueOf(4));
        assertThat(StarkPedersenHash.hashOnElements(elements))
                .isEqualTo(new BigInteger(
                        "66bd4335902683054d08a0572747ea78ebd9e531536fb43125424ca9f902084", 16));
    }

    @Test
    @DisplayName("hash_on_elements([]) equals pedersen(0, 0)")
    void emptyElements() {
        assertThat(StarkPedersenHash.hashOnElements(List.of()))
                .isEqualTo(StarkPedersenHash.pedersen(BigInteger.ZERO, BigInteger.ZERO));
    }

    @Test
    @DisplayName("out-of-field input is rejected instead of silently reduced")
    void outOfFieldRejected() {
        assertThatThrownBy(() -> StarkPedersenHash.pedersen(StarkPedersenHash.P, BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
