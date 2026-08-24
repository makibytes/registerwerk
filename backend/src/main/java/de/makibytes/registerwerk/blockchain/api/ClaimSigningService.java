package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
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
 *   claimHash  = keccak256(abi.encode(identity, topic, data))
 *   prefixed   = keccak256("\x19Ethereum Signed Message:\n32" + claimHash)
 *   signature  = ecSign(prefixed, issuerKey)  → (v, r, s) packed as 65 bytes
 * </pre>
 *
 * <p>Both {@code claimData} and {@code claimHash}'s input are ABI-encoded using Web3j's
 * {@code FunctionEncoder} to produce the exact byte layout Solidity's {@code abi.encode()}
 * would — real {@code abi.encode}, not {@code abi.encodePacked}: a plain byte concatenation of
 * a raw 20-byte address + a 32-byte topic + the data bytes (this class's previous claimHash
 * computation) is neither. {@code abi.encode} left-pads the address to a full 32-byte word and,
 * because {@code data} is a dynamic type, prepends an offset word and a length word ahead of its
 * (32-byte-aligned) content — three extra words a flat concatenation omits entirely. OnchainID's
 * {@code ClaimIssuer.isClaimValid} recomputes {@code dataHash} via real {@code abi.encode} before
 * recovering the signer, so a mismatched encoding here means the recovered signer never matches
 * the actual issuer wallet and {@code isClaimValid} returns {@code false} for every claim —
 * permanently, regardless of whether the correct private key signed it.
 * </p>
 */
@Service
public class ClaimSigningService {

    private final WalletSigner walletSigner;

    public ClaimSigningService(WalletSigner walletSigner) {
        this.walletSigner = walletSigner;
    }

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
        return signClaim(null, identityAddress, topic, expiresAt);
    }

    /**
     * Signs a claim with the registry wallet of the given chain. ERC-3643 token
     * contracts verify claims against the TrustedIssuersRegistry of <em>their</em>
     * chain — a claim signed by a different chain's wallet (or "any" wallet) would
     * not match the trusted issuer and the holder's transfers would fail on-chain.
     *
     * @param chainConfigId chain whose registry wallet must issue the claim;
     *                      {@code null} falls back to any configured EVM wallet
     */
    public SignedClaim signClaim(java.util.UUID chainConfigId, String identityAddress,
                                 long topic, Instant expiresAt) {
        EvmSigner signer = chainConfigId != null
                ? walletSigner.evmSignerForChain(chainConfigId)
                : walletSigner.evmSignerForAnyEvm();
        String issuer = signer.address();

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

        // claimHash = keccak256(abi.encode(identityAddress, topic, data)) — real ABI encoding
        // (see class javadoc for why a flat concatenation, this method's previous approach,
        // produces different bytes and makes every issued claim permanently unverifiable).
        Function claimHashInputFn = new Function(
                "",
                Arrays.asList(
                        new Address(identityAddress),
                        new Uint256(BigInteger.valueOf(topic)),
                        new DynamicBytes(data)
                ),
                Collections.emptyList()
        );
        String encodedHashInputWithSelector = FunctionEncoder.encode(claimHashInputFn);
        byte[] hashInput = Numeric.hexStringToByteArray(encodedHashInputWithSelector.substring(10));
        byte[] claimHash = Hash.sha3(hashInput);

        // EIP-191 prefix: "\x19Ethereum Signed Message:\n32" + claimHash → sign
        Sign.SignatureData sig = signer.signPrefixedHash(claimHash);
        byte[] sigBytes = new byte[65];
        System.arraycopy(sig.getR(), 0, sigBytes, 0, 32);
        System.arraycopy(sig.getS(), 0, sigBytes, 32, 32);
        sigBytes[64] = sig.getV()[0];

        return new SignedClaim(claimDataHex, Numeric.toHexString(sigBytes), issuer);
    }
}
