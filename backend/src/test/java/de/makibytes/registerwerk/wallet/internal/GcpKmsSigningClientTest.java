package de.makibytes.registerwerk.wallet.internal;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.ApiExceptionFactory;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.PublicKey;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import io.grpc.Status;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GcpKmsSigningClient - adapter over the Cloud KMS client (mocked)")
class GcpKmsSigningClientTest {

    private static final String KEY = FakeKmsSigningClient.KEY;

    private KeyManagementServiceClient gcp;
    private GcpKmsSigningClient client;
    private ECKeyPair key;

    @BeforeEach
    void setUp() throws Exception {
        gcp = mock(KeyManagementServiceClient.class);
        client = new GcpKmsSigningClient(() -> gcp);
        key = Keys.createEcKeyPair();
    }

    private static long crc(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    private static byte[] point(ECKeyPair key) {
        byte[] xy = Numeric.toBytesPadded(key.getPublicKey(), 64);
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(xy, 0, point, 1, 64);
        return point;
    }

    private static String pem(AlgorithmIdentifier algorithm, byte[] point) throws Exception {
        byte[] der = new SubjectPublicKeyInfo(algorithm, point).getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static AlgorithmIdentifier secp256k1() {
        return new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256k1);
    }

    private static PublicKey.Builder publicKey(String pem, CryptoKeyVersionAlgorithm algorithm) {
        return PublicKey.newBuilder().setPem(pem).setAlgorithm(algorithm)
                .setPemCrc32C(Int64Value.of(crc(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("getPublicKey returns the uncompressed secp256k1 point from the PEM")
    void publicKeyParsed() throws Exception {
        byte[] point = point(key);
        when(gcp.getPublicKey(KEY)).thenReturn(
                publicKey(pem(secp256k1(), point), CryptoKeyVersionAlgorithm.EC_SIGN_SECP256K1_SHA256).build());
        assertThat(client.getPublicKey(KEY)).isEqualTo(point);
    }

    @Test
    @DisplayName("a key with another algorithm, curve, or a corrupted PEM is refused")
    void publicKeyRejections() throws Exception {
        String good = pem(secp256k1(), point(key));
        when(gcp.getPublicKey(KEY)).thenReturn(
                publicKey(good, CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256).build());
        assertThatThrownBy(() -> client.getPublicKey(KEY)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.PERMANENT);
                    assertThat(e).hasMessageContaining("EC_SIGN_SECP256K1_SHA256");
                });

        ECPoint p256 = NISTNamedCurves.getByName("P-256").getG();
        String p256Pem = pem(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey,
                X9ObjectIdentifiers.prime256v1), p256.getEncoded(false));
        when(gcp.getPublicKey(KEY)).thenReturn(
                publicKey(p256Pem, CryptoKeyVersionAlgorithm.EC_SIGN_SECP256K1_SHA256).build());
        assertThatThrownBy(() -> client.getPublicKey(KEY)).isInstanceOf(KmsSigningException.class)
                .hasMessageContaining("secp256k1");

        when(gcp.getPublicKey(KEY)).thenReturn(
                publicKey(good, CryptoKeyVersionAlgorithm.EC_SIGN_SECP256K1_SHA256)
                        .setPemCrc32C(Int64Value.of(1)).build());
        assertThatThrownBy(() -> client.getPublicKey(KEY)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.TRANSIENT));
    }

    @Test
    @DisplayName("signDigest sends the 32-byte hash as the sha256 digest with its CRC32C and returns the DER signature")
    void signRequestAndResponse() {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) 7);
        byte[] der = FakeKmsSigningClient.der(BigInteger.TEN, BigInteger.TWO);
        when(gcp.asymmetricSign(any(AsymmetricSignRequest.class))).thenReturn(AsymmetricSignResponse.newBuilder()
                .setName(KEY).setSignature(ByteString.copyFrom(der))
                .setSignatureCrc32C(Int64Value.of(crc(der))).setVerifiedDigestCrc32C(true).build());

        assertThat(client.signDigest(KEY, digest)).isEqualTo(der);

        ArgumentCaptor<AsymmetricSignRequest> request = ArgumentCaptor.forClass(AsymmetricSignRequest.class);
        verify(gcp).asymmetricSign(request.capture());
        assertThat(request.getValue().getName()).isEqualTo(KEY);
        assertThat(request.getValue().getDigest().getSha256().toByteArray()).isEqualTo(digest);
        assertThat(request.getValue().getDigestCrc32C().getValue()).isEqualTo(crc(digest));
    }

    @Test
    @DisplayName("integrity failures (digest not verified, signature CRC, wrong key name) are transient")
    void integrityFailures() {
        byte[] digest = new byte[32];
        byte[] der = FakeKmsSigningClient.der(BigInteger.TEN, BigInteger.TWO);
        AsymmetricSignResponse.Builder ok = AsymmetricSignResponse.newBuilder()
                .setName(KEY).setSignature(ByteString.copyFrom(der))
                .setSignatureCrc32C(Int64Value.of(crc(der))).setVerifiedDigestCrc32C(true);

        when(gcp.asymmetricSign(any(AsymmetricSignRequest.class))).thenReturn(ok.clone().setVerifiedDigestCrc32C(false).build());
        assertThatThrownBy(() -> client.signDigest(KEY, digest)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.TRANSIENT));

        when(gcp.asymmetricSign(any(AsymmetricSignRequest.class))).thenReturn(ok.clone().setSignatureCrc32C(Int64Value.of(5)).build());
        assertThatThrownBy(() -> client.signDigest(KEY, digest)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.TRANSIENT));

        when(gcp.asymmetricSign(any(AsymmetricSignRequest.class))).thenReturn(ok.clone().setName("other").build());
        assertThatThrownBy(() -> client.signDigest(KEY, digest)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.TRANSIENT));
    }

    @Test
    @DisplayName("gRPC status codes map to retryable (UNAVAILABLE/ABORTED/...) and timeout/permanent kinds")
    void statusMapping() {
        byte[] digest = new byte[32];
        assertKind(Status.Code.UNAVAILABLE, KmsSigningException.Kind.TRANSIENT, digest);
        assertKind(Status.Code.ABORTED, KmsSigningException.Kind.TRANSIENT, digest);
        assertKind(Status.Code.RESOURCE_EXHAUSTED, KmsSigningException.Kind.TRANSIENT, digest);
        assertKind(Status.Code.INTERNAL, KmsSigningException.Kind.TRANSIENT, digest);
        assertKind(Status.Code.DEADLINE_EXCEEDED, KmsSigningException.Kind.TIMEOUT, digest);
        assertKind(Status.Code.PERMISSION_DENIED, KmsSigningException.Kind.PERMANENT, digest);
        assertKind(Status.Code.NOT_FOUND, KmsSigningException.Kind.PERMANENT, digest);
        assertKind(Status.Code.INVALID_ARGUMENT, KmsSigningException.Kind.PERMANENT, digest);
        assertKind(Status.Code.FAILED_PRECONDITION, KmsSigningException.Kind.PERMANENT, digest);
    }

    private void assertKind(Status.Code code, KmsSigningException.Kind expected, byte[] digest) {
        ApiException api = ApiExceptionFactory.createException(new RuntimeException(code.name()),
                GrpcStatusCode.of(code), false);
        doThrow(api).when(gcp).asymmetricSign(any(AsymmetricSignRequest.class));
        assertThatThrownBy(() -> client.signDigest(KEY, digest)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).as(code.name()).isEqualTo(expected));
        doThrow(api).when(gcp).getPublicKey(eq(KEY));
        assertThatThrownBy(() -> client.getPublicKey(KEY)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> assertThat(e.kind()).as(code.name()).isEqualTo(expected));
    }

    @Test
    @DisplayName("the Cloud KMS client is created lazily (no credentials needed at bean creation) and only once")
    void lazyClient() throws Exception {
        AtomicInteger created = new AtomicInteger();
        GcpKmsSigningClient lazy = new GcpKmsSigningClient(() -> {
            created.incrementAndGet();
            return gcp;
        });
        assertThat(created.get()).isZero();
        when(gcp.getPublicKey(KEY)).thenReturn(publicKey(pem(secp256k1(), point(key)),
                CryptoKeyVersionAlgorithm.EC_SIGN_SECP256K1_SHA256).build());
        lazy.getPublicKey(KEY);
        lazy.getPublicKey(KEY);
        assertThat(created.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("missing Application Default Credentials surface as a permanent KmsSigningException")
    void credentialFailure() {
        GcpKmsSigningClient broken = new GcpKmsSigningClient(() -> {
            throw new UncheckedIOException(new java.io.IOException("The Application Default Credentials are not available"));
        });
        assertThatThrownBy(() -> broken.getPublicKey(KEY)).isInstanceOfSatisfying(KmsSigningException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(KmsSigningException.Kind.PERMANENT);
                    assertThat(e).hasMessageContaining("Application Default Credentials");
                });
    }

    @Test
    @DisplayName("key-version resource names are validated, digests must be 32 bytes")
    void validation() {
        assertThat(client.provider()).isEqualTo("gcp");
        assertThat(client.isValidKeyReference(KEY)).isTrue();
        assertThat(client.isValidKeyReference("projects/p/locations/l/keyRings/r/cryptoKeys/k")).isFalse();
        assertThat(client.isValidKeyReference("projects/p/locations/l/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1/../x")).isFalse();
        assertThat(client.isValidKeyReference("")).isFalse();
        assertThat(client.isValidKeyReference(null)).isFalse();
        assertThatThrownBy(() -> client.signDigest(KEY, new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.signDigest("nope", new byte[32])).isInstanceOf(IllegalArgumentException.class);
        assertThat(CustomNamedCurves.getByName("secp256k1")).isNotNull();
    }
}
