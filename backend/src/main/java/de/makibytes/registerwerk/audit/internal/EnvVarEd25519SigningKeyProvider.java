package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;

/**
 * Dev/test-only Ed25519 signing key, deterministically derived from an env-configured seed
 * so restarts don't rotate the key and orphan every previously signed row's verifiability.
 *
 * <p>Disabled by default ({@code registerwerk.audit.signing.provider} unset) — unlike {@code
 * wallet.internal.EnvVarKekProvider} (which is production-blocked but always active as the
 * fallback), forcing this on unconditionally would misrepresent an experimental, self-signed
 * scheme as a real external chain-of-custody anchor. Set {@code
 * registerwerk.audit.signing.provider=ENV_VAR} explicitly for dev/test use, or {@code =GCP_KMS}
 * (see {@link GcpKmsEd25519SigningKeyProvider}) for a real KMS-backed anchor in production.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.audit.signing.provider", havingValue = "ENV_VAR")
class EnvVarEd25519SigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvVarEd25519SigningKeyProvider.class);
    private static final String ALGORITHM = "Ed25519";

    private final KeyPair keyPair;
    private final Instant loadedAt = Instant.now();

    EnvVarEd25519SigningKeyProvider(@Value("${registerwerk.audit.signing.seed:}") String seed) {
        if (seed == null || seed.isBlank()) {
            log.warn("registerwerk.audit.signing.seed is not set; using an ephemeral key that will "
                    + "NOT survive a restart — every previously signed row will fail verification "
                    + "after the next restart. Set a stable seed before relying on this.");
            seed = java.util.UUID.randomUUID().toString();
        }
        this.keyPair = deriveKeyPair(seed);
        log.warn("Using EnvVarEd25519SigningKeyProvider for audit-chain signing — suitable only for "
                + "dev/test. Wire a KMS/HSM-backed SigningKeyProvider for a real external chain-of-"
                + "custody anchor in production.");
    }

    @Override
    public String name() {
        return "ENV_VAR_ED25519";
    }

    @Override
    public byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    @Override
    public byte[] publicKey() {
        return keyPair.getPublic().getEncoded();
    }

    /**
     * This provider derives the same key deterministically from a static seed every time, so
     * there is no real "created at" — this JVM's own load time is the best available proxy,
     * and will reset to "just now" on every restart. Not meaningful for detecting an actually
     * stale/unrotated key; a KMS-backed provider's real key-metadata timestamp is what the
     * {@code registerwerk_audit_signing_key_age_seconds} gauge is meant to track in production.
     */
    @Override
    public Instant createdAt() {
        return loadedAt;
    }

    @Override
    public boolean verify(byte[] data, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(keyPair.getPublic());
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            log.error("Ed25519 signature verification threw: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Derives a deterministic Ed25519 keypair from an arbitrary seed string: the seed is
     * hashed to 256 bits and used to seed a {@code SHA1PRNG} {@link SecureRandom}, which
     * {@link KeyPairGenerator} then consumes — the JDK has no direct "raw 32-byte private key
     * in, keypair out" API for Ed25519, so a seeded PRNG is the standard way to get a
     * reproducible keypair using only standard {@code java.security} APIs.
     */
    private static KeyPair deriveKeyPair(String seedString) {
        try {
            byte[] seedBytes = MessageDigest.getInstance("SHA-256").digest(seedString.getBytes(StandardCharsets.UTF_8));
            SecureRandom deterministicRandom = SecureRandom.getInstance("SHA1PRNG");
            deterministicRandom.setSeed(seedBytes);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            kpg.initialize(NamedParameterSpec.ED25519, deterministicRandom);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 key derivation failed", e);
        }
    }
}
