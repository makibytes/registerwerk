package de.makibytes.registerwerk.application.blockchain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

/**
 * Signs ERC-3643 KYC/AML claims on behalf of the registry operator acting as a Trusted Issuer.
 *
 * <p>The claim signature follows the ERC-735 / ONCHAINID specification:
 * <pre>
 *   claimData  = abi.encode(topic, scheme=1, issuer, expiresAt, "")
 *   claimHash  = keccak256(abi.encodePacked(identity, topic, data))
 *   prefixed   = keccak256("\x19Ethereum Signed Message:\n32" + claimHash)
 *   signature  = ecSign(prefixed, issuerKey)  → (v, r, s) packed as 65 bytes
 * </pre>
 *
 * <p>The claim data is ABI-encoded using Web3j's {@code DefaultFunctionEncoder}
 * to produce the same byte layout as Solidity's {@code abi.encode()}. This allows
 * the ONCHAINID contract's {@code addClaim()} to verify the signature correctly.
 * </p>
 */
@Service
public class ClaimSigningService {

    /** The private key of the wallet registered as Trusted Issuer in every T-REX suite. */
    @Value("${registerwerk.blockchain.signing-key:}")
    private String signingKeyHex;

    /**
     * Result record returned by {@link #signClaim}.
     *
     * @param claimData      hex-encoded ABI-encoded claim data bytes
     * @param claimSignature hex-encoded ECDSA signature
     * @param issuerAddress  checksummed address of the signing wallet
     */
    public record SignedClaim(String claimData, String claimSignature, String issuerAddress) {}

    /**
     * Signs a claim for the given identity/topic combination.
     *
     * @param identityAddress  on-chain ONCHAINID contract address of the subject
     * @param topic            claim topic (e.g. 1 = KYC)
     * @param expiresAt        optional expiry; null means no expiry
     * @return a {@link SignedClaim} containing hex-encoded data and signature
     */
    public SignedClaim signClaim(String identityAddress, long topic, Instant expiresAt) {
        if (signingKeyHex == null || signingKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "registerwerk.blockchain.signing-key is not configured; cannot sign claims");
        }

        Credentials credentials = Credentials.create(signingKeyHex);
        String issuer = credentials.getAddress();

        long expiry = expiresAt != null ? expiresAt.getEpochSecond() : 0L;

        // claimData = abi.encode(topic, scheme, issuer, expiresAt, uri)
        // Encode using Web3j's ABI encoder to match Solidity's abi.encode() output exactly.
        Function claimDataFn = new Function(
                "", // function name not used for plain ABI encoding
                Arrays.asList(
                        new Uint256(BigInteger.valueOf(topic)),
                        new Uint256(BigInteger.ONE),            // scheme = 1 (ECDSA)
                        new Address(issuer),
                        new Uint256(BigInteger.valueOf(expiry)),
                        new Utf8String("")                      // uri
                ),
                Collections.emptyList()
        );
        // FunctionEncoder.encode prepends a 4-byte selector; strip it for raw abi.encode output
        String encodedWithSelector = FunctionEncoder.encode(claimDataFn);
        byte[] data = Numeric.hexStringToByteArray(encodedWithSelector.substring(10)); // drop "0x" + 8 hex chars
        String claimDataHex = Numeric.toHexString(data);

        // claimHash = keccak256(abi.encodePacked(identityAddress, topic, data))
        byte[] packed = concat(
                Numeric.hexStringToByteArray(identityAddress),
                toBigEndian32(BigInteger.valueOf(topic)),
                data);
        byte[] claimHash = Hash.sha3(packed);

        // EIP-191 prefix: "\x19Ethereum Signed Message:\n32" + claimHash → sign
        Sign.SignatureData sig = Sign.signPrefixedMessage(claimHash, credentials.getEcKeyPair());
        byte[] sigBytes = new byte[65];
        System.arraycopy(sig.getR(), 0, sigBytes, 0, 32);
        System.arraycopy(sig.getS(), 0, sigBytes, 32, 32);
        sigBytes[64] = sig.getV()[0];

        return new SignedClaim(claimDataHex, Numeric.toHexString(sigBytes), issuer);
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static byte[] toBigEndian32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] padded = new byte[32];
        int srcPos = Math.max(0, raw.length - 32);
        System.arraycopy(raw, srcPos, padded, 32 - (raw.length - srcPos), raw.length - srcPos);
        return padded;
    }

    private static byte[] hexToBytes(String hex) {
        return Numeric.hexStringToByteArray("0x" + hex);
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] result = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }
}
