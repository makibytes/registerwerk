package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.internal.WalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DEK-wrap/unwrap envelope logic in {@link WalletStorage} — previously
 * entirely untested (finding #11, Phase 8), including the highest-blast-radius code path in
 * the repo: the encrypted-at-rest storage of every operator signing key. A local
 * {@link StubKekProvider} stands in for a real KMS, matching the real interface contract
 * (fresh IV per {@code wrap()} call, so a rewrap always produces different ciphertext bytes
 * even though it unwraps to the identical plaintext DEK).
 */
class WalletStorageTest {

    @TempDir
    Path tempDir;

    private WalletStorage storage;
    private StubKekProvider kekProvider;

    @BeforeEach
    void setUp() {
        WalletProperties props = new WalletProperties();
        props.setStorageDir(tempDir.toString());
        kekProvider = new StubKekProvider();
        storage = new WalletStorage(props, kekProvider);
    }

    // ── EVM round-trip ────────────────────────────────────────────────────────

    @Test
    @DisplayName("storeEvm/loadEvm round-trips the same key pair")
    void evmRoundTrip() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();

        String relativePath = storage.storeEvm(id, pair);
        Credentials loaded = storage.loadEvm(relativePath);

        assertThat(loaded.getEcKeyPair().getPrivateKey()).isEqualTo(pair.getPrivateKey());
        assertThat(Files.exists(tempDir.resolve(id + ".dek.bin"))).isTrue();
    }

    @Test
    @DisplayName("importEvmRaw stores a key derived from the given hex string")
    void importEvmRaw() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        String hex = "0x" + pair.getPrivateKey().toString(16);
        UUID id = UUID.randomUUID();

        String relativePath = storage.importEvmRaw(id, hex);
        Credentials loaded = storage.loadEvm(relativePath);

        assertThat(loaded.getEcKeyPair().getPrivateKey()).isEqualTo(pair.getPrivateKey());
    }

    @Test
    @DisplayName("importEvmKeystore decrypts the operator-supplied keystore then re-stores it under the master KEK")
    void importEvmKeystore() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID originalId = UUID.randomUUID();
        String userPassword = "correct-horse-battery-staple";
        org.web3j.crypto.WalletFile userWf = org.web3j.crypto.Wallet.createLight(userPassword, pair);
        String keystoreJson = org.web3j.protocol.ObjectMapperFactory.getObjectMapper().writeValueAsString(userWf);

        UUID newId = UUID.randomUUID();
        String relativePath = storage.importEvmKeystore(newId, keystoreJson, userPassword);
        Credentials loaded = storage.loadEvm(relativePath);

        assertThat(loaded.getEcKeyPair().getPrivateKey()).isEqualTo(pair.getPrivateKey());
    }

    @Test
    @DisplayName("exportEvmKeystore re-encrypts under an operator-chosen password, independently decryptable")
    void exportEvmKeystore() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeEvm(id, pair);

        String exported = storage.exportEvmKeystore(relativePath, "export-password-123");
        org.web3j.crypto.WalletFile exportedWf =
                org.web3j.protocol.ObjectMapperFactory.getObjectMapper().readValue(exported, org.web3j.crypto.WalletFile.class);
        ECKeyPair decrypted = org.web3j.crypto.Wallet.decrypt("export-password-123", exportedWf);

        assertThat(decrypted.getPrivateKey()).isEqualTo(pair.getPrivateKey());
    }

    @Test
    @DisplayName("exportEvmRaw returns the 0x-prefixed private key hex")
    void exportEvmRaw() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeEvm(id, pair);

        String raw = storage.exportEvmRaw(relativePath);

        assertThat(raw).isEqualTo("0x" + pair.getPrivateKey().toString(16));
    }

    // ── Solana / Canton (shared version-2 envelope) round-trip ────────────────

    @Test
    @DisplayName("storeSolana/loadSolana round-trips the same 64-byte secret key")
    void solanaRoundTrip() throws Exception {
        byte[] secretKey = new byte[64];
        new SecureRandom().nextBytes(secretKey);
        UUID id = UUID.randomUUID();

        String relativePath = storage.storeSolana(id, secretKey);
        byte[] loaded = storage.loadSolana(relativePath);

        assertThat(loaded).isEqualTo(secretKey);
    }

    @Test
    @DisplayName("storeCanton/loadCanton round-trips the party ID and JWT")
    void cantonRoundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeCanton(id, "Alice::abc123", "eyJhbGciOiJIUzI1NiJ9.test.sig");

        WalletStorage.CantonContext ctx = storage.loadCanton(relativePath);

        assertThat(ctx.partyId()).isEqualTo("Alice::abc123");
        assertThat(ctx.jwt()).isEqualTo("eyJhbGciOiJIUzI1NiJ9.test.sig");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes the keystore file")
    void delete() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeEvm(id, pair);
        assertThat(Files.exists(tempDir.resolve(relativePath))).isTrue();

        storage.delete(relativePath);

        assertThat(Files.exists(tempDir.resolve(relativePath))).isFalse();
    }

    // ── KEK rotation (finding #9, Phase 8) ─────────────────────────────────────

    @Test
    @DisplayName("rewrapDek(EVM) re-wraps the sidecar DEK; the wallet still decrypts identically afterward")
    void rewrapDek_evm_rotatesSidecarAndPreservesDecryptability() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeEvm(id, pair);
        byte[] wrappedBefore = Files.readAllBytes(tempDir.resolve(id + ".dek.bin"));

        boolean rotated = storage.rewrapDek(relativePath, true);

        assertThat(rotated).isTrue();
        byte[] wrappedAfter = Files.readAllBytes(tempDir.resolve(id + ".dek.bin"));
        assertThat(wrappedAfter).isNotEqualTo(wrappedBefore);
        assertThat(storage.loadEvm(relativePath).getEcKeyPair().getPrivateKey()).isEqualTo(pair.getPrivateKey());
    }

    @Test
    @DisplayName("rewrapDek(EVM) is a no-op for a legacy keystore with no .dek.bin sidecar")
    void rewrapDek_evm_legacyKeystoreSkipped() throws Exception {
        ECKeyPair pair = Keys.createEcKeyPair();
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeEvm(id, pair);
        Files.delete(tempDir.resolve(id + ".dek.bin")); // simulate a pre-envelope-encryption keystore

        boolean rotated = storage.rewrapDek(relativePath, true);

        assertThat(rotated).isFalse();
    }

    @Test
    @DisplayName("rewrapDek(Solana) re-wraps the envelope's wrappedDek field; the wallet still decrypts identically afterward")
    void rewrapDek_solana_rotatesEnvelopeAndPreservesDecryptability() throws Exception {
        byte[] secretKey = new byte[64];
        new SecureRandom().nextBytes(secretKey);
        UUID id = UUID.randomUUID();
        String relativePath = storage.storeSolana(id, secretKey);
        String envelopeBefore = Files.readString(tempDir.resolve(relativePath));

        boolean rotated = storage.rewrapDek(relativePath, false);

        assertThat(rotated).isTrue();
        String envelopeAfter = Files.readString(tempDir.resolve(relativePath));
        assertThat(envelopeAfter).isNotEqualTo(envelopeBefore);
        assertThat(storage.loadSolana(relativePath)).isEqualTo(secretKey);
    }

    @Test
    @DisplayName("rewrapDek(non-EVM) is a no-op for a legacy version-1 envelope")
    void rewrapDek_solana_legacyEnvelopeSkipped() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, String> legacyEnvelope = Map.of(
                "version", "1",
                "type", "solana",
                "salt", HexFormat.of().formatHex(new byte[16]),
                "iv", HexFormat.of().formatHex(new byte[12]),
                "ciphertext", HexFormat.of().formatHex(new byte[16]));
        String relativePath = id + ".json";
        Files.writeString(tempDir.resolve(relativePath),
                "{" + legacyEnvelope.entrySet().stream()
                        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                        .collect(Collectors.joining(",")) + "}");

        boolean rotated = storage.rewrapDek(relativePath, false);

        assertThat(rotated).isFalse();
    }

    /** Matches the real {@link de.makibytes.registerwerk.wallet.api.KekProvider} contract: a
     *  fresh random IV per {@code wrap()} call, default (unwrap-then-wrap) {@code rewrap()}. */
    private static final class StubKekProvider implements KekProvider {
        private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

        public String name() { return "STUB_KEK"; }

        public byte[] wrap(byte[] plaintextDek) {
            try {
                byte[] iv = new byte[12];
                new SecureRandom().nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 32), "AES"),
                        new GCMParameterSpec(128, iv));
                byte[] ciphertext = c.doFinal(plaintextDek);
                return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public byte[] unwrap(byte[] wrappedDek) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(wrappedDek);
                byte[] iv = new byte[12];
                buf.get(iv);
                byte[] ciphertext = new byte[buf.remaining()];
                buf.get(ciphertext);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 32), "AES"),
                        new GCMParameterSpec(128, iv));
                return c.doFinal(ciphertext);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
