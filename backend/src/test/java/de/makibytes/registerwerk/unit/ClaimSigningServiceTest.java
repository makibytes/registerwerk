package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService.SignedClaim;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import de.makibytes.registerwerk.wallet.internal.SoftwareEvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ERC-3643 claim signing. The interesting part isn't that some bytes
 * come back — it's that the signature is a real ECDSA signature over the exact claim
 * hash the ONCHAINID contract will recompute on-chain, recoverable back to the signing
 * wallet's address. A test that only checks "non-null" would pass even if the signing
 * key, the hash, or the ABI encoding were subtly wrong.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSigningServiceTest {

    @Mock
    private WalletSigner walletSigner;

    private ClaimSigningService service;
    private Credentials credentials;
    private EvmSigner signer;

    @BeforeEach
    void setUp() {
        service = new ClaimSigningService(walletSigner);
        // Fixed, non-secret test key (32 bytes, well below curve order) — deterministic fixture only.
        credentials = Credentials.create("0x" + "11".repeat(32));
        signer = new SoftwareEvmSigner(credentials);
    }

    @Test
    void signClaim_withoutChain_usesAnyEvmWallet() {
        when(walletSigner.evmSignerForAnyEvm()).thenReturn(signer);

        SignedClaim signed = service.signClaim("0x" + "11".repeat(20), 1L, null);

        assertThat(signed.issuerAddress()).isEqualTo(credentials.getAddress());
    }

    @Test
    void signClaim_withChain_usesThatChainsWallet() {
        UUID chainConfigId = UUID.randomUUID();
        when(walletSigner.evmSignerForChain(chainConfigId)).thenReturn(signer);

        SignedClaim signed = service.signClaim(chainConfigId, "0x" + "22".repeat(20), 1L, null);

        assertThat(signed.issuerAddress()).isEqualTo(credentials.getAddress());
        assertThat(signed.claimSignature()).startsWith("0x");
    }

    @Test
    void signClaim_signatureRecoversToTheSigningWalletsAddress() throws SignatureException {
        when(walletSigner.evmSignerForAnyEvm()).thenReturn(signer);
        String identity = "0x" + "33".repeat(20);
        long topic = 1L;

        SignedClaim signed = service.signClaim(identity, topic, null);

        // Rebuild claimHash exactly as the service does — real abi.encode(address,uint256,bytes),
        // not a flat byte concatenation — then recover the signer's public key from the signature
        // and confirm it maps back to the reported issuer address. This is the regression test
        // for the fix that replaced an abi.encodePacked-style concatenation (which never matched
        // OnchainID's real ClaimIssuer.isClaimValid encoding, making every issued claim
        // permanently unverifiable on-chain) with Web3j's FunctionEncoder-based real ABI encoding.
        byte[] data = Numeric.hexStringToByteArray(signed.claimData());
        Function claimHashInputFn = new Function(
                "",
                Arrays.asList(new Address(identity), new Uint256(BigInteger.valueOf(topic)), new DynamicBytes(data)),
                Collections.emptyList()
        );
        String encodedWithSelector = FunctionEncoder.encode(claimHashInputFn);
        byte[] hashInput = Numeric.hexStringToByteArray(encodedWithSelector.substring(10));
        byte[] claimHash = Hash.sha3(hashInput);

        byte[] sigBytes = Numeric.hexStringToByteArray(signed.claimSignature());
        assertThat(sigBytes).hasSize(65);
        byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);
        byte[] v = new byte[] { sigBytes[64] };
        Sign.SignatureData sig = new Sign.SignatureData(v, r, s);

        BigInteger recoveredKey = Sign.signedPrefixedMessageToKey(claimHash, sig);
        String recoveredAddress = "0x" + Keys.getAddress(recoveredKey);

        assertThat(recoveredAddress).isEqualToIgnoringCase(signed.issuerAddress());
    }

    @Test
    void signClaim_claimHashUsesRealAbiEncodeNotPacked() throws SignatureException {
        // A flat concatenation (the previous, broken approach) omits the address's left-padding
        // to 32 bytes and the dynamic `data` parameter's offset/length words entirely — so
        // reconstructing the OLD (wrong) way must NOT recover the same signer, proving the fix
        // actually changed what gets signed rather than merely refactoring the call site.
        when(walletSigner.evmSignerForAnyEvm()).thenReturn(signer);
        String identity = "0x" + "66".repeat(20);
        long topic = 1L;

        SignedClaim signed = service.signClaim(identity, topic, null);
        byte[] data = Numeric.hexStringToByteArray(signed.claimData());

        byte[] packed = new byte[20 + 32 + data.length];
        System.arraycopy(Numeric.hexStringToByteArray(identity), 0, packed, 0, 20);
        byte[] topicWord = new byte[32];
        topicWord[31] = (byte) topic;
        System.arraycopy(topicWord, 0, packed, 20, 32);
        System.arraycopy(data, 0, packed, 52, data.length);
        byte[] wrongHash = Hash.sha3(packed);

        byte[] sigBytes = Numeric.hexStringToByteArray(signed.claimSignature());
        byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);
        byte[] v = new byte[] { sigBytes[64] };
        Sign.SignatureData sig = new Sign.SignatureData(v, r, s);

        BigInteger recoveredKey = Sign.signedPrefixedMessageToKey(wrongHash, sig);
        String recoveredAddress = "0x" + Keys.getAddress(recoveredKey);
        assertThat(recoveredAddress).isNotEqualToIgnoringCase(signed.issuerAddress());
    }

    @Test
    void signClaim_differentTopics_produceDifferentClaimDataAndSignature() {
        when(walletSigner.evmSignerForAnyEvm()).thenReturn(signer);
        String identity = "0x" + "44".repeat(20);

        SignedClaim topic1 = service.signClaim(identity, 1L, null);
        SignedClaim topic2 = service.signClaim(identity, 2L, null);

        assertThat(topic1.claimData()).isNotEqualTo(topic2.claimData());
        assertThat(topic1.claimSignature()).isNotEqualTo(topic2.claimSignature());
    }

    @Test
    void signClaim_withExpiry_encodesDifferentClaimDataThanNoExpiry() {
        when(walletSigner.evmSignerForAnyEvm()).thenReturn(signer);
        String identity = "0x" + "55".repeat(20);
        Instant expiry = Instant.ofEpochSecond(1_800_000_000L);

        SignedClaim withExpiry = service.signClaim(identity, 1L, expiry);
        SignedClaim withoutExpiry = service.signClaim(identity, 1L, null);

        assertThat(withExpiry.claimData()).isNotEqualTo(withoutExpiry.claimData());
    }
}
