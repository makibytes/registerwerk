package de.makibytes.registerwerk.audit.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies Phase 5 finding #8: entry_sig now has a real, testable (dev/test-grade) signer. */
@DisplayName("EnvVarEd25519SigningKeyProvider unit tests")
class EnvVarEd25519SigningKeyProviderTest {

    @Test
    @DisplayName("a signature it produces verifies successfully against the same data")
    void sign_thenVerify_succeeds() {
        EnvVarEd25519SigningKeyProvider provider = new EnvVarEd25519SigningKeyProvider("test-seed-1");
        byte[] data = "some entry_hash bytes".getBytes(StandardCharsets.UTF_8);

        byte[] signature = provider.sign(data);

        assertThat(provider.verify(data, signature)).isTrue();
    }

    @Test
    @DisplayName("verification fails when the data has been tampered with")
    void verify_failsForTamperedData() {
        EnvVarEd25519SigningKeyProvider provider = new EnvVarEd25519SigningKeyProvider("test-seed-2");
        byte[] data = "original data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = provider.sign(data);

        boolean valid = provider.verify("tampered data".getBytes(StandardCharsets.UTF_8), signature);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("the same seed deterministically derives the same keypair across instances")
    void sameSeed_derivesSameKeyPair() {
        EnvVarEd25519SigningKeyProvider first = new EnvVarEd25519SigningKeyProvider("stable-seed");
        EnvVarEd25519SigningKeyProvider second = new EnvVarEd25519SigningKeyProvider("stable-seed");

        assertThat(first.publicKey()).isEqualTo(second.publicKey());

        // A signature from the first instance must verify against the second — proving a
        // restarted application (new instance, same seed) can verify rows signed before restart.
        byte[] data = "cross-instance data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = first.sign(data);
        assertThat(second.verify(data, signature)).isTrue();
    }

    @Test
    @DisplayName("different seeds derive different keypairs")
    void differentSeeds_deriveDifferentKeyPairs() {
        EnvVarEd25519SigningKeyProvider a = new EnvVarEd25519SigningKeyProvider("seed-a");
        EnvVarEd25519SigningKeyProvider b = new EnvVarEd25519SigningKeyProvider("seed-b");

        assertThat(Arrays.equals(a.publicKey(), b.publicKey())).isFalse();
    }

    @Test
    @DisplayName("a blank seed falls back to an ephemeral key rather than failing")
    void blankSeed_fallsBackToEphemeralKey() {
        EnvVarEd25519SigningKeyProvider provider = new EnvVarEd25519SigningKeyProvider("");
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);

        assertThat(provider.verify(data, provider.sign(data))).isTrue();
    }
}
