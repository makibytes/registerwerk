package de.makibytes.registerwerk.audit.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the parts of {@link GcpKmsEd25519SigningKeyProvider} that don't require a
 * live GCP project/credentials: the fail-fast config guard and the PEM-decoding helper. The
 * constructor's actual KMS calls (getPublicKey/getCryptoKeyVersion) can't be exercised without
 * a real GCP KMS key — not verifiable in this environment.
 */
@DisplayName("GcpKmsEd25519SigningKeyProvider — config guard and PEM decoding")
class GcpKmsEd25519SigningKeyProviderTest {

    @Test
    @DisplayName("throws before touching GCP KMS when key-version is blank")
    void constructor_rejectsBlankKeyVersion() {
        assertThatThrownBy(() -> new GcpKmsEd25519SigningKeyProvider(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registerwerk.audit.signing.gcp-kms.key-version");
    }

    @Test
    @DisplayName("throws before touching GCP KMS when key-version is null")
    void constructor_rejectsNullKeyVersion() {
        assertThatThrownBy(() -> new GcpKmsEd25519SigningKeyProvider(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registerwerk.audit.signing.gcp-kms.key-version");
    }

    @Test
    @DisplayName("decodes a real Ed25519 SubjectPublicKeyInfo PEM back to its original DER bytes")
    void decodeSubjectPublicKeyInfoPem_roundTrips() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        byte[] expectedDer = keyPair.getPublic().getEncoded();

        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(expectedDer);
        String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";

        byte[] decoded = GcpKmsEd25519SigningKeyProvider.decodeSubjectPublicKeyInfoPem(pem);

        assertThat(decoded).isEqualTo(expectedDer);
    }
}
