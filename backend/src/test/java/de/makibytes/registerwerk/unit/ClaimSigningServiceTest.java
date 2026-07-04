package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService.SignedClaim;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Arrays;
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

    @BeforeEach
    void setUp() {
        service = new ClaimSigningService(walletSigner);
        // Fixed, non-secret test key (32 bytes, well below curve order) — deterministic fixture only.
        credentials = Credentials.create("0x" + "11".repeat(32));
    }

    @Test
    void signClaim_withoutChain_usesAnyEvmWallet() {
        when(walletSigner.credentialsForAnyEvm()).thenReturn(credentials);

        SignedClaim signed = service.signClaim("0x" + "11".repeat(20), 1L, null);

        assertThat(signed.issuerAddress()).isEqualTo(credentials.getAddress());
    }

    @Test
    void signClaim_withChain_usesThatChainsWallet() {
        UUID chainConfigId = UUID.randomUUID();
        when(walletSigner.credentialsForChain(chainConfigId)).thenReturn(credentials);

        SignedClaim signed = service.signClaim(chainConfigId, "0x" + "22".repeat(20), 1L, null);

        assertThat(signed.issuerAddress()).isEqualTo(credentials.getAddress());
        assertThat(signed.claimSignature()).startsWith("0x");
    }

    @Test
    void signClaim_signatureRecoversToTheSigningWalletsAddress() throws SignatureException {
        when(walletSigner.credentialsForAnyEvm()).thenReturn(credentials);
        String identity = "0x" + "33".repeat(20);
        long topic = 1L;

        SignedClaim signed = service.signClaim(identity, topic, null);

        // Rebuild claimHash exactly as the service does, then recover the signer's public
        // key from the signature and confirm it maps back to the reported issuer address.
        byte[] data = Numeric.hexStringToByteArray(signed.claimData());
        byte[] packed = concat(
                Numeric.hexStringToByteArray(identity),
                toBigEndian32(BigInteger.valueOf(topic)),
                data);
        byte[] claimHash = Hash.sha3(packed);

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
    void signClaim_differentTopics_produceDifferentClaimDataAndSignature() {
        when(walletSigner.credentialsForAnyEvm()).thenReturn(credentials);
        String identity = "0x" + "44".repeat(20);

        SignedClaim topic1 = service.signClaim(identity, 1L, null);
        SignedClaim topic2 = service.signClaim(identity, 2L, null);

        assertThat(topic1.claimData()).isNotEqualTo(topic2.claimData());
        assertThat(topic1.claimSignature()).isNotEqualTo(topic2.claimSignature());
    }

    @Test
    void signClaim_withExpiry_encodesDifferentClaimDataThanNoExpiry() {
        when(walletSigner.credentialsForAnyEvm()).thenReturn(credentials);
        String identity = "0x" + "55".repeat(20);
        Instant expiry = Instant.ofEpochSecond(1_800_000_000L);

        SignedClaim withExpiry = service.signClaim(identity, 1L, expiry);
        SignedClaim withoutExpiry = service.signClaim(identity, 1L, null);

        assertThat(withExpiry.claimData()).isNotEqualTo(withoutExpiry.claimData());
    }

    private static byte[] toBigEndian32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] padded = new byte[32];
        int srcPos = Math.max(0, raw.length - 32);
        System.arraycopy(raw, srcPos, padded, 32 - (raw.length - srcPos), raw.length - srcPos);
        return padded;
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
