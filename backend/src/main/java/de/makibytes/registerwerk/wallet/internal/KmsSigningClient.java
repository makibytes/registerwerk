package de.makibytes.registerwerk.wallet.internal;

/**
 * Provider SPI for a cloud KMS that holds non-exportable secp256k1 keys (T7-05). The first adapter is
 * GCP Cloud KMS ({@link GcpKmsSigningClient}); an AWS KMS adapter only has to implement this interface
 * and be selected through {@code registerwerk.wallet.kms.provider} - {@link KmsEvmSigner} and every
 * caller above it stay unchanged.
 *
 * <p>Implementations must be thread-safe, must authenticate with ambient credentials only (workload
 * identity / instance role, never key files in configuration), must verify the transport integrity the
 * provider offers, and must never log digests or key material. Timeouts and retries are enforced by
 * {@link KmsSignerService}, not here.
 */
public interface KmsSigningClient {

    /** Provider id as used in {@code registerwerk.wallet.kms.provider}, lower case (e.g. {@code gcp}). */
    String provider();

    /** Cheap syntactic check of a provider-specific key reference (no network call). */
    boolean isValidKeyReference(String keyReference);

    /**
     * Returns the public key as an uncompressed secp256k1 point: 65 bytes, {@code 0x04 || X || Y}.
     *
     * @throws KmsSigningException if the key is missing, not a secp256k1 signing key, or unreachable
     */
    byte[] getPublicKey(String keyReference) throws KmsSigningException;

    /**
     * Signs a 32-byte pre-hashed digest (keccak256 of the Ethereum payload) and returns the ASN.1 DER
     * ECDSA signature exactly as the KMS produced it (low-s normalisation and recovery id are the
     * caller's job).
     */
    byte[] signDigest(String keyReference, byte[] digest) throws KmsSigningException;
}
