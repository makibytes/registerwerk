package de.makibytes.registerwerk.wallet.api;

/**
 * Port for Key-Encryption-Key (KEK) providers.
 * In production, wire one of: AwsKmsKekProvider, AzureKeyVaultKekProvider,
 * GcpKmsKekProvider, or Pkcs11HsmKekProvider.
 * The current EnvVarKekProvider is dev-only and is rejected when
 * REGISTERWERK_PRODUCTION_MODE=true (see ProductionReadinessCheck).
 */
public interface KekProvider extends de.makibytes.registerwerk.shared.EnvelopeCipher.KeyWrapper {

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

    /** Key-version labels this provider currently knows (active first); empty when it exposes no versions. */
    default java.util.Set<String> configuredVersions() {
        return activeVersion().map(java.util.Set::of).orElse(java.util.Set.of());
    }

    /**
     * Stops using {@code version} for unwrapping, in this process, once the KEK-retire guard has verified that
     * nothing references it. Providers whose versions are managed in the cloud console return false: the operator
     * then disables the version there.
     */
    default boolean disableVersion(String version) {
        return false;
    }
}
