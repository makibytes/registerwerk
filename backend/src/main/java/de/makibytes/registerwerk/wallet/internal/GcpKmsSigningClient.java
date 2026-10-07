package de.makibytes.registerwerk.wallet.internal;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.kms.v1.PublicKey;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * GCP Cloud KMS adapter (T7-05): asymmetric signing with {@code EC_SIGN_SECP256K1_SHA256} keys (HSM
 * protection level). The keccak256 hash of the Ethereum payload is passed as the pre-computed
 * {@code sha256} digest - the KMS signs the 32 bytes as given. Authentication is Application Default
 * Credentials only (GKE Workload Identity in production); no key files are configured anywhere.
 *
 * <p>The Cloud KMS client is created lazily on first use so the bean exists without credentials and a
 * missing identity surfaces through the readiness probe, not as a context failure. Request and response
 * integrity is verified with the CRC32C fields Cloud KMS offers; an integrity failure is transient (the
 * caller retries) and never yields a signature. Retries and the call deadline belong to
 * {@link KmsSignerService}; the client's own deadline is only a backstop.
 */
public final class GcpKmsSigningClient implements KmsSigningClient, AutoCloseable {

    /** {@code projects/P/locations/L/keyRings/R/cryptoKeys/K/cryptoKeyVersions/N}. */
    private static final Pattern KEY_VERSION = Pattern.compile(
            "projects/[A-Za-z0-9_.:-]+/locations/[A-Za-z0-9_-]+/keyRings/[A-Za-z0-9_-]+"
                    + "/cryptoKeys/[A-Za-z0-9_-]+/cryptoKeyVersions/[0-9]+");

    private final Supplier<KeyManagementServiceClient> factory;
    private volatile KeyManagementServiceClient client;

    /** Production: lazily creates a client with Application Default Credentials and the given backstop deadline. */
    public GcpKmsSigningClient(Duration rpcDeadline) {
        this(() -> create(rpcDeadline));
    }

    /** Tests / custom transports: any Cloud KMS client, created on first use. */
    public GcpKmsSigningClient(Supplier<KeyManagementServiceClient> factory) {
        this.factory = factory;
    }

    @Override
    public String provider() {
        return "gcp";
    }

    @Override
    public boolean isValidKeyReference(String keyReference) {
        return keyReference != null && KEY_VERSION.matcher(keyReference).matches();
    }

    @Override
    public byte[] getPublicKey(String keyReference) {
        requireKeyReference(keyReference);
        PublicKey publicKey = call("getPublicKey", () -> client().getPublicKey(keyReference));
        if (publicKey.getAlgorithm() != CryptoKeyVersionAlgorithm.EC_SIGN_SECP256K1_SHA256) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT, "Cloud KMS key version "
                    + keyReference + " has algorithm " + publicKey.getAlgorithm()
                    + ", expected EC_SIGN_SECP256K1_SHA256");
        }
        if (publicKey.hasPemCrc32C()
                && publicKey.getPemCrc32C().getValue() != crc32c(publicKey.getPemBytes().toByteArray())) {
            throw new KmsSigningException(KmsSigningException.Kind.TRANSIENT,
                    "Cloud KMS public key failed its CRC32C integrity check (corrupted in transit)");
        }
        return parsePem(publicKey.getPem());
    }

    @Override
    public byte[] signDigest(String keyReference, byte[] digest) {
        requireKeyReference(keyReference);
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("KMS digest must be exactly 32 bytes");
        }
        AsymmetricSignRequest request = AsymmetricSignRequest.newBuilder()
                .setName(keyReference)
                .setDigest(Digest.newBuilder().setSha256(ByteString.copyFrom(digest)))
                .setDigestCrc32C(Int64Value.of(crc32c(digest)))
                .build();
        AsymmetricSignResponse response = call("asymmetricSign", () -> client().asymmetricSign(request));
        if (!response.getVerifiedDigestCrc32C()) {
            throw new KmsSigningException(KmsSigningException.Kind.TRANSIENT,
                    "Cloud KMS did not verify the digest checksum (request corrupted in transit)");
        }
        if (!response.getName().isEmpty() && !response.getName().equals(keyReference)) {
            throw new KmsSigningException(KmsSigningException.Kind.TRANSIENT,
                    "Cloud KMS signed with a different key version than requested");
        }
        byte[] signature = response.getSignature().toByteArray();
        if (signature.length == 0) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT, "Cloud KMS returned an empty signature");
        }
        if (response.hasSignatureCrc32C() && response.getSignatureCrc32C().getValue() != crc32c(signature)) {
            throw new KmsSigningException(KmsSigningException.Kind.TRANSIENT,
                    "Cloud KMS signature failed its CRC32C integrity check (corrupted in transit)");
        }
        return signature;
    }

    @Override
    public void close() {
        KeyManagementServiceClient current = client;
        if (current != null) {
            current.close();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private KeyManagementServiceClient client() {
        KeyManagementServiceClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = factory.get();
                    client = current;
                }
            }
        }
        return current;
    }

    private static KeyManagementServiceClient create(Duration rpcDeadline) {
        try {
            KeyManagementServiceSettings.Builder settings = KeyManagementServiceSettings.newBuilder();
            settings.getPublicKeySettings().setSimpleTimeoutNoRetriesDuration(rpcDeadline);
            settings.asymmetricSignSettings().setSimpleTimeoutNoRetriesDuration(rpcDeadline);
            return KeyManagementServiceClient.create(settings.build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (KmsSigningException e) {
            throw e;
        } catch (ApiException e) {
            StatusCode.Code code = e.getStatusCode().getCode();
            KmsSigningException.Kind kind = switch (code) {
                case DEADLINE_EXCEEDED -> KmsSigningException.Kind.TIMEOUT;
                case UNAVAILABLE, ABORTED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN -> KmsSigningException.Kind.TRANSIENT;
                default -> KmsSigningException.Kind.PERMANENT;
            };
            throw new KmsSigningException(kind, "Cloud KMS " + operation + " failed (" + code + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT,
                    "Cloud KMS client error during " + operation + ": " + e.getMessage(), e);
        }
    }

    private static byte[] parsePem(String pem) {
        try {
            StringBuilder base64 = new StringBuilder();
            for (String line : pem.split("\\R")) {
                if (!line.startsWith("-----") && !line.isBlank()) {
                    base64.append(line.trim());
                }
            }
            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(Base64.getDecoder().decode(base64.toString()));
            boolean secp256k1 = X9ObjectIdentifiers.id_ecPublicKey.equals(info.getAlgorithm().getAlgorithm())
                    && SECObjectIdentifiers.secp256k1.equals(
                            ASN1ObjectIdentifier.getInstance(info.getAlgorithm().getParameters()));
            if (!secp256k1) {
                throw new IllegalArgumentException("not a secp256k1 key");
            }
            return info.getPublicKeyData().getBytes();
        } catch (RuntimeException e) {
            throw new KmsSigningException(KmsSigningException.Kind.PERMANENT,
                    "Cloud KMS public key is not a secp256k1 SubjectPublicKeyInfo");
        }
    }

    private static void requireKeyReference(String keyReference) {
        if (keyReference == null || !KEY_VERSION.matcher(keyReference).matches()) {
            throw new IllegalArgumentException(
                    "not a Cloud KMS key version name (projects/P/locations/L/keyRings/R/cryptoKeys/K/cryptoKeyVersions/N)");
        }
    }

    private static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }
}
