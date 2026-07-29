package de.makibytes.registerwerk.audit.api;

import java.time.Instant;

/**
 * Port for the audit hash-chain's Ed25519 signing key (external chain-of-custody anchor).
 *
 * <p>Without this, the hash chain's tamper-evidence rests entirely on database-level access
 * control: anyone who can rewrite {@code audit_event} at the Postgres level (disable the WORM
 * trigger, edit rows, restore from an altered backup) can also recompute a fully
 * self-consistent chain that passes verification. A signature over each row's {@code
 * entry_hash}, produced by a key this application never persists in the same database,
 * closes that gap.
 *
 * <p>In production, wire a KMS/HSM-backed implementation. Unlike {@code wallet.api.KekProvider}
 * (which has a working provider for all three major clouds), only {@code
 * GcpKmsEd25519SigningKeyProvider} exists here — as of the SDK versions this project depends
 * on, AWS KMS and Azure Key Vault simply don't support Ed25519/EdDSA asymmetric keys at all
 * (their signing key specs cover only RSA and NIST/SECG elliptic curves). An AWS/Azure-hosted
 * deployment that wants this control can point at a GCP KMS key regardless of where the rest
 * of the stack runs, or use HashiCorp Vault's Transit engine (not implemented — Vault isn't a
 * dependency anywhere else in this codebase). The current {@code EnvVarEd25519SigningKeyProvider}
 * remains dev/test-only and, unlike the wallet KEK providers, stays opt-in (disabled by
 * default) rather than production-blocked — mandating GCP KMS specifically would be too
 * cloud-opinionated for a codebase that otherwise treats AWS/Azure/GCP as equally first-class.
 */
public interface SigningKeyProvider {

    /** Human-readable name logged at startup. */
    String name();

    /** Signs {@code data} (the audit row's {@code entry_hash}) and returns the raw signature. */
    byte[] sign(byte[] data);

    /** The public key, for independent verification outside this application. */
    byte[] publicKey();

    /** Verifies a signature produced by {@link #sign(byte[])} over the same data. */
    boolean verify(byte[] data, byte[] signature);

    /**
     * When the active signing key was created/last rotated — backs the
     * {@code registerwerk_audit_signing_key_age_seconds} gauge (an unrotated signing key is a
     * real operational risk for a tamper-evidence anchor). KMS-backed providers return the real
     * key-material creation timestamp from the provider's own metadata; the dev-only env-var
     * provider has no such concept and returns this JVM's start time as a best-effort proxy —
     * documented as a limitation there, not a substitute for real rotation tracking.
     */
    Instant createdAt();
}
