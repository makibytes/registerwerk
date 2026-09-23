package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.PaymasterVoucherDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Java voucher digest to {@code EwpgPaymaster.getHash}: the same vector is asserted by
 * {@code contracts/test/ecosystem/EwpgPaymaster.t.sol::test_getHash_pinnedVector} (and was
 * cross-checked with {@code cast abi-encode}/{@code cast keccak}).
 */
@DisplayName("PaymasterVoucherDigest — byte-exact with EwpgPaymaster.getHash")
class GasSponsorshipVoucherDigestTest {

    static final String PINNED = "0xee2ca28f7c343c5f133cbe451ce09a964bb4a54fbb14add797f6db6db841b53e";

    static PaymasterVoucherDigest.UserOpFields vectorOp() {
        return new PaymasterVoucherDigest.UserOpFields(
                "0x1111111111111111111111111111111111111111",
                BigInteger.valueOf(7),
                Numeric.hexStringToByteArray("0x7702"),
                Numeric.hexStringToByteArray("0xdeadbeef"),
                BigInteger.valueOf(100_000),       // verificationGasLimit (high half)
                BigInteger.valueOf(200_000),       // callGasLimit (low half)
                BigInteger.valueOf(150_000),
                BigInteger.valueOf(80_000),
                BigInteger.valueOf(21_000),
                BigInteger.valueOf(1_000_000_000L),   // maxPriorityFeePerGas (high half)
                BigInteger.valueOf(10_000_000_000L)); // maxFeePerGas (low half)
    }

    @Test
    @DisplayName("matches the Solidity-pinned vector")
    void matchesPinnedVector() {
        byte[] policyId = Hash.sha3("policy".getBytes(StandardCharsets.US_ASCII));
        byte[] digest = PaymasterVoucherDigest.digest(vectorOp(), 31337,
                "0x2222222222222222222222222222222222222222", policyId,
                1_700_000_000L, 0L, BigInteger.valueOf(10_000_000_000L));
        assertThat(Numeric.toHexString(digest)).isEqualTo(PINNED);
    }

    @Test
    @DisplayName("binds the chain id and the paymaster address")
    void bindsChainAndPaymaster() {
        byte[] policyId = Hash.sha3("policy".getBytes(StandardCharsets.US_ASCII));
        BigInteger cap = BigInteger.valueOf(10_000_000_000L);
        String base = Numeric.toHexString(PaymasterVoucherDigest.digest(vectorOp(), 31337,
                "0x2222222222222222222222222222222222222222", policyId, 1_700_000_000L, 0L, cap));
        assertThat(Numeric.toHexString(PaymasterVoucherDigest.digest(vectorOp(), 1,
                "0x2222222222222222222222222222222222222222", policyId, 1_700_000_000L, 0L, cap))).isNotEqualTo(base);
        assertThat(Numeric.toHexString(PaymasterVoucherDigest.digest(vectorOp(), 31337,
                "0x3333333333333333333333333333333333333333", policyId, 1_700_000_000L, 0L, cap))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("parses decimal and hex quantities and rejects out-of-range values")
    void quantities() {
        assertThat(PaymasterVoucherDigest.quantity("0x10")).isEqualTo(BigInteger.valueOf(16));
        assertThat(PaymasterVoucherDigest.quantity("16")).isEqualTo(BigInteger.valueOf(16));
        assertThatThrownBy(() -> PaymasterVoucherDigest.quantity("-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
