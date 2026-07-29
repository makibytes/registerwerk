package de.makibytes.registerwerk.audit.internal;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Real KMS-backed Ed25519 signing for the audit hash-chain — the alternative {@link
 * EnvVarEd25519SigningKeyProvider}'s own Javadoc says has never existed until now.
 *
 * <p><b>Only GCP Cloud KMS is offered</b>: as of the SDK versions this project depends on,
 * neither AWS KMS nor Azure Key Vault support Ed25519/EdDSA asymmetric signing keys at all —
 * their signing key specs are limited to RSA and NIST/SECG elliptic curves (confirmed by
 * inspecting {@code software.amazon.awssdk.services.kms.model.KeySpec} and {@code
 * com.azure.security.keyvault.keys.models.KeyCurveName}, neither of which declares an Ed25519
 * variant). GCP Cloud KMS's {@code CryptoKeyVersionAlgorithm.EC_SIGN_ED25519} is genuinely
 * real and supported. A deployment on AWS/Azure that still wants this control has two honest
 * options: point this provider at a GCP KMS key regardless of where the rest of the stack
 * runs (a single small cross-cloud call, not a real operational burden), or use HashiCorp
 * Vault's Transit secrets engine (which does support Ed25519) — not implemented here since
 * Vault isn't a dependency anywhere else in this codebase.
 *
 * <p>Activate: {@code registerwerk.audit.signing.provider=GCP_KMS}
 * Requires: {@code registerwerk.audit.signing.gcp-kms.key-version} = the full resource name
 * {@code projects/P/locations/L/keyRings/R/cryptoKeys/K/cryptoKeyVersions/V} of an EC_SIGN_ED25519
 * key version, and GOOGLE_APPLICATION_CREDENTIALS or GKE Workload Identity for auth.
 */
@Component("gcpKmsEd25519SigningKeyProvider")
@ConditionalOnProperty(name = "registerwerk.audit.signing.provider", havingValue = "GCP_KMS")
class GcpKmsEd25519SigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(GcpKmsEd25519SigningKeyProvider.class);

    private final String keyVersionResourceName;
    private final byte[] publicKeyEncoded;
    private final PublicKey publicKey;
    private final Instant keyCreatedAt;
    private final KeyManagementServiceClient client;

    GcpKmsEd25519SigningKeyProvider(
            @Value("${registerwerk.audit.signing.gcp-kms.key-version:}") String keyVersionResourceName) {
        if (keyVersionResourceName == null || keyVersionResourceName.isBlank()) {
            throw new IllegalStateException(
                "registerwerk.audit.signing.gcp-kms.key-version is required when "
                    + "registerwerk.audit.signing.provider=GCP_KMS");
        }
        this.keyVersionResourceName = keyVersionResourceName;

        // Held for the provider's lifetime rather than opened per sign() call: this feeds the
        // audit hash-chain appender, which fires on every state-mutating request, so per-call
        // client creation would pay gRPC channel setup (credential resolution, TLS handshake) on
        // a hot path.
        try {
            this.client = KeyManagementServiceClient.create();

            com.google.cloud.kms.v1.PublicKey gcpPublicKey = client.getPublicKey(keyVersionResourceName);
            this.publicKeyEncoded = decodeSubjectPublicKeyInfoPem(gcpPublicKey.getPem());
            this.publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKeyEncoded));

            com.google.cloud.kms.v1.CryptoKeyVersion version = client.getCryptoKeyVersion(keyVersionResourceName);
            this.keyCreatedAt = Instant.ofEpochSecond(
                    version.getCreateTime().getSeconds(), version.getCreateTime().getNanos());
        } catch (IOException e) {
            throw new UncheckedIOException("GCP KMS client creation failed for " + keyVersionResourceName, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                "GCP KMS key " + keyVersionResourceName + " returned a public key that isn't a "
                    + "valid Ed25519 key — is EC_SIGN_ED25519 the correct algorithm for this key version?", e);
        }
        log.info("GcpKmsEd25519SigningKeyProvider initialized with keyVersion={}", keyVersionResourceName);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    @Override
    public String name() {
        return "GCP_KMS_ED25519";
    }

    @Override
    public byte[] sign(byte[] data) {
        // Ed25519 (PureEdDSA) signs the raw message directly — no pre-hashing, unlike the
        // RSA/EC digest-based signing this SDK's convenience overloads are built around. That's
        // why this builds a request with setData(...) rather than using the client's
        // asymmetricSign(name, Digest) convenience method, which has nowhere to put raw data.
        AsymmetricSignRequest request = AsymmetricSignRequest.newBuilder()
                .setName(keyVersionResourceName)
                .setData(ByteString.copyFrom(data))
                .build();
        AsymmetricSignResponse response = client.asymmetricSign(request);
        return response.getSignature().toByteArray();
    }

    @Override
    public byte[] publicKey() {
        return publicKeyEncoded;
    }

    @Override
    public boolean verify(byte[] data, byte[] signatureBytes) {
        // Verified locally against the cached public key rather than round-tripping to KMS on
        // every verification — Ed25519 verification is fast and this key is not secret.
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            log.error("Ed25519 signature verification threw: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Instant createdAt() {
        return keyCreatedAt;
    }

    static byte[] decodeSubjectPublicKeyInfoPem(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
