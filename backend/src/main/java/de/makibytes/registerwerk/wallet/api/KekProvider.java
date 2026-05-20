package de.makibytes.registerwerk.wallet.api;

/**
 * Port for Key-Encryption-Key (KEK) providers.
 * In production, wire one of: AwsKmsKekProvider, AzureKeyVaultKekProvider,
 * GcpKmsKekProvider, or Pkcs11HsmKekProvider.
 * The current EnvVarKekProvider is dev-only and is rejected when
 * REGISTERWERK_PRODUCTION_MODE=true (see ProductionReadinessCheck).
 */
public interface KekProvider {

    /** Human-readable name logged at startup. */
    String name();

    /**
     * Wraps (encrypts) a plaintext data-encryption key.
     * The returned ciphertext is opaque and must only be unwrapped by this provider.
     */
    byte[] wrap(byte[] plaintextDek);

    /**
     * Unwraps (decrypts) a previously wrapped DEK.
     * Implementations must NOT cache plaintext DEKs beyond the call boundary.
     */
    byte[] unwrap(byte[] wrappedDek);

    /**
     * Re-wraps a DEK under a new KEK version (key rotation).
     * Default: unwrap then wrap — override for atomic KMS-side re-wrap.
     */
    default byte[] rewrap(byte[] wrappedDek) {
        return wrap(unwrap(wrappedDek));
    }
}
