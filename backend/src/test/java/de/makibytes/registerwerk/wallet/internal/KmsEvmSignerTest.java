package de.makibytes.registerwerk.wallet.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KmsEvmSigner - address derivation, DER to (r,s,v), low-s, fail closed")
class KmsEvmSignerTest {

    private static final BigInteger HALF_N = Sign.CURVE_PARAMS.getN().shiftRight(1);

    private ECKeyPair key;
    private FakeKmsSigningClient kms;
    private SimpleMeterRegistry registry;
    private KmsSignerService service;

    @BeforeEach
    void setUp() throws Exception {
        key = Keys.createEcKeyPair();
        kms = new FakeKmsSigningClient(key);
        registry = new SimpleMeterRegistry();
        KmsProperties props = new KmsProperties();
        props.setSigner("kms");
        props.getKms().setRetryBackoff(Duration.ofMillis(1));
        service = new KmsSignerService(props, Optional.of(kms), registry);
    }

    private String expectedAddress() {
        return Keys.toChecksumAddress(Credentials.create(key).getAddress());
    }

    @Test
    @DisplayName("the Ethereum address is derived from the KMS public key")
    void addressDerivedFromPublicKey() {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        assertThat(signer.address()).isEqualTo(expectedAddress());
        assertThat(kms.publicKeyCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a configured address that the KMS key does not control is refused")
    void expectedAddressMismatchRefused() {
        String other = Credentials.create("0x" + "11".repeat(32)).getAddress();
        assertThatThrownBy(() -> KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not control");
        assertThat(KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, Credentials.create(key).getAddress()).address())
                .isEqualTo(expectedAddress());
    }

    @Test
    @DisplayName("300 random digests: low-s, ecrecover returns the KMS address, both parities occur")
    void manyDigestsRecoverToKmsAddress() {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        SecureRandom random = new SecureRandom();
        boolean parity0 = false;
        boolean parity1 = false;
        for (int i = 0; i < 300; i++) {
            byte[] digest = new byte[32];
            random.nextBytes(digest);
            Sign.SignatureData sig = signer.signDigest(digest);
            BigInteger r = new BigInteger(1, sig.getR());
            BigInteger s = new BigInteger(1, sig.getS());
            assertThat(s).as("EIP-2 low-s").isLessThanOrEqualTo(HALF_N);
            int recId = sig.getV()[0] - 27;
            assertThat(recId).isIn(0, 1);
            parity0 |= recId == 0;
            parity1 |= recId == 1;
            BigInteger recovered = Sign.recoverFromSignature(recId, new ECDSASignature(r, s), digest);
            assertThat("0x" + Keys.getAddress(recovered)).isEqualToIgnoringCase(signer.address());
        }
        assertThat(kms.highSReturned.get()).as("the fake KMS really returned high-s signatures").isGreaterThan(50);
        assertThat(parity0 && parity1).isTrue();
        assertThat(registry.get("registerwerk_kms_sign_total").tag("outcome", "success").counter().count())
                .isEqualTo(300.0);
    }

    @Test
    @DisplayName("fails closed (and counts it) when neither parity recovers to the wallet address")
    void failsClosedWhenSignedByAnotherKey() throws Exception {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        kms.signWith = Keys.createEcKeyPair();
        assertThatThrownBy(() -> signer.signDigest(Hash.sha3("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not recover");
        assertThat(registry.get("registerwerk_kms_sign_failures_total").tag("reason", "address_mismatch")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("malformed or out-of-range signatures are rejected, not signed over")
    void rejectsGarbageSignatures() {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        byte[] digest = new byte[32];
        kms.garbage = new byte[] {1, 2, 3};
        assertThatThrownBy(() -> signer.signDigest(digest)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid ECDSA signature");
        kms.garbage = FakeKmsSigningClient.der(BigInteger.ZERO, BigInteger.ONE);
        assertThatThrownBy(() -> signer.signDigest(digest)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-range");
        kms.garbage = FakeKmsSigningClient.der(BigInteger.ONE, Sign.CURVE_PARAMS.getN());
        assertThatThrownBy(() -> signer.signDigest(digest)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-range");
        assertThat(registry.get("registerwerk_kms_sign_failures_total").tag("reason", "invalid_signature")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("only 32-byte digests are signed")
    void rejectsWrongDigestLength() {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        assertThatThrownBy(() -> signer.signDigest(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.signDigest(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(kms.signCalls.get()).isZero();
    }

    @Test
    @DisplayName("legacy (EIP-155) and EIP-1559 transactions carry a signature that recovers to the KMS address")
    void signsLegacyAndTypedTransactions() throws Exception {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        String to = "0x" + "cc".repeat(20);
        for (int nonce = 0; nonce < 10; nonce++) {
            RawTransaction legacy = RawTransaction.createTransaction(BigInteger.valueOf(nonce),
                    BigInteger.valueOf(20_000_000_000L), BigInteger.valueOf(100_000), to, "0xdeadbeef");
            RawTransaction typed = RawTransaction.createTransaction(137L, BigInteger.valueOf(nonce),
                    BigInteger.valueOf(100_000), to, BigInteger.ZERO, "0xdeadbeef",
                    BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(60_000_000_000L));
            for (RawTransaction tx : new RawTransaction[] {legacy, typed}) {
                byte[] signed = signer.signTransaction(tx, 137L);
                SignedRawTransaction decoded =
                        (SignedRawTransaction) TransactionDecoder.decode(Numeric.toHexString(signed));
                assertThat(decoded.getFrom()).isEqualToIgnoringCase(signer.address());
            }
        }
    }

    @Test
    @DisplayName("EIP-191 prefixed hashes recover to the KMS address")
    void signsPrefixedHash() {
        KmsEvmSigner signer = KmsEvmSigner.create(service, FakeKmsSigningClient.KEY, null);
        byte[] hash = Hash.sha3("claim".getBytes(StandardCharsets.UTF_8));
        Sign.SignatureData sig = signer.signPrefixedHash(hash);
        byte[] prefix = "\u0019Ethereum Signed Message:\n32".getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[prefix.length + 32];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(hash, 0, input, prefix.length, 32);
        byte[] digest = Hash.sha3(input);
        BigInteger pub = Sign.recoverFromSignature(sig.getV()[0] - 27,
                new ECDSASignature(new BigInteger(1, sig.getR()), new BigInteger(1, sig.getS())), digest);
        assertThat("0x" + Keys.getAddress(pub)).isEqualToIgnoringCase(signer.address());
    }
}
