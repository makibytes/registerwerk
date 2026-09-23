package de.makibytes.registerwerk.asset.internal;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Byte-exact Java mirror of {@code EwpgPaymaster.getHash}:
 * <pre>
 *   opHash = keccak256(abi.encode(sender, nonce, keccak256(initCode), keccak256(callData),
 *                                 accountGasLimits, paymasterVerificationGasLimit,
 *                                 paymasterPostOpGasLimit, preVerificationGas, gasFees))
 *   digest = keccak256(abi.encode(VOUCHER_TYPEHASH, opHash, chainId, paymaster, policyId,
 *                                 validUntil, validAfter, maxFeePerGasCap))
 * </pre>
 * Every argument is a static ABI type, so {@code abi.encode} is a plain concatenation of
 * 32-byte words. The digest deliberately excludes the voucher signature bytes (they live in
 * {@code paymasterAndData}, which is part of {@code userOpHash} — a signature cannot sign a hash
 * that contains it). The signer signs it EIP-191 ({@code personal_sign}) style.
 * Pinned against Solidity by {@code GasSponsorshipVoucherDigestTest} /
 * {@code EwpgPaymasterTest.test_getHash_pinnedVector}.
 */
public final class PaymasterVoucherDigest {

    public static final byte[] VOUCHER_TYPEHASH =
            Hash.sha3("EwpgPaymasterVoucher(v1)".getBytes(StandardCharsets.US_ASCII));

    private PaymasterVoucherDigest() {}

    /** The UserOperation fields the voucher covers (everything except the signatures). */
    public record UserOpFields(
            String sender, BigInteger nonce, byte[] initCode, byte[] callData,
            BigInteger verificationGasLimit, BigInteger callGasLimit,
            BigInteger paymasterVerificationGasLimit, BigInteger paymasterPostOpGasLimit,
            BigInteger preVerificationGas, BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) {}

    public static byte[] digest(UserOpFields op, long chainId, String paymaster, byte[] policyId,
                                long validUntil, long validAfter, BigInteger maxFeePerGasCap) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        word(inner, address(op.sender()));
        word(inner, op.nonce());
        word(inner, Hash.sha3(op.initCode()));
        word(inner, Hash.sha3(op.callData()));
        word(inner, packHighLow(op.verificationGasLimit(), op.callGasLimit()));
        word(inner, op.paymasterVerificationGasLimit());
        word(inner, op.paymasterPostOpGasLimit());
        word(inner, op.preVerificationGas());
        word(inner, packHighLow(op.maxPriorityFeePerGas(), op.maxFeePerGas()));
        byte[] opHash = Hash.sha3(inner.toByteArray());

        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        word(outer, VOUCHER_TYPEHASH);
        word(outer, opHash);
        word(outer, BigInteger.valueOf(chainId));
        word(outer, address(paymaster));
        word(outer, policyId);
        word(outer, BigInteger.valueOf(validUntil));
        word(outer, BigInteger.valueOf(validAfter));
        word(outer, maxFeePerGasCap);
        return Hash.sha3(outer.toByteArray());
    }

    /** On-chain policy id for a {@code GasSponsorshipPolicy} row: {@code keccak256(id.toString())}. */
    public static byte[] policyId(java.util.UUID policyRowId) {
        return Hash.sha3(policyRowId.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Parses a decimal or 0x-hex uint256 quantity. */
    public static BigInteger quantity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing quantity");
        BigInteger v = value.startsWith("0x") || value.startsWith("0X")
                ? new BigInteger(value.substring(2), 16)
                : new BigInteger(value);
        requireUint(v, 256);
        return v;
    }

    /** {@code bytes32(high << 128 | low)} — the packing of accountGasLimits and gasFees. */
    static byte[] packHighLow(BigInteger high, BigInteger low) {
        requireUint(high, 128);
        requireUint(low, 128);
        return Numeric.toBytesPadded(high.shiftLeft(128).or(low), 32);
    }

    private static BigInteger address(String hex) {
        byte[] bytes = Numeric.hexStringToByteArray(hex);
        if (bytes.length != 20) throw new IllegalArgumentException("Not a 20-byte address: " + hex);
        return new BigInteger(1, bytes);
    }

    private static void word(ByteArrayOutputStream out, BigInteger value) {
        requireUint(value, 256);
        out.writeBytes(Numeric.toBytesPadded(value, 32));
    }

    private static void word(ByteArrayOutputStream out, byte[] bytes32) {
        if (bytes32.length != 32) throw new IllegalArgumentException("Expected 32 bytes");
        out.writeBytes(bytes32);
    }

    private static void requireUint(BigInteger value, int bits) {
        if (value == null || value.signum() < 0 || value.bitLength() > bits) {
            throw new IllegalArgumentException("Value out of uint" + bits + " range: " + value);
        }
    }
}
