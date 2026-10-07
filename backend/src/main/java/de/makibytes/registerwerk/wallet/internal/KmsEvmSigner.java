package de.makibytes.registerwerk.wallet.internal;

import org.web3j.crypto.ECDSASignature;

import java.util.Objects;

/**
 * EVM signer whose private key lives in a cloud KMS and never in this process (T7-05). The Ethereum
 * address is derived from the KMS public key; each 32-byte keccak digest is signed remotely
 * ({@link KmsSignerService}), and the DER signature is converted to {@code (r, s, v)} with EIP-2 low-s
 * normalisation and a recovery id that is verified against that address ({@link RecoverableEvmSigner}),
 * so a swapped or wrong key can never produce a signature that is accepted as valid.
 */
public final class KmsEvmSigner extends RecoverableEvmSigner {

    private final KmsSignerService service;
    private final String keyReference;

    private KmsEvmSigner(KmsSignerService service, String keyReference, String address) {
        super(address);
        this.service = service;
        this.keyReference = keyReference;
    }

    /**
     * @param expectedAddress the wallet's stored address, or null/blank to accept the key's own address;
     *                        when given it must be the address the KMS key controls (fail closed otherwise)
     */
    public static KmsEvmSigner create(KmsSignerService service, String keyReference, String expectedAddress) {
        Objects.requireNonNull(service, "service");
        if (keyReference == null || keyReference.isBlank()) {
            throw new IllegalArgumentException("KMS key reference must not be blank");
        }
        String reference = keyReference.trim();
        String derived = KmsSignerService.addressOf(service.publicKey(reference));
        if (expectedAddress != null && !expectedAddress.isBlank() && !derived.equalsIgnoreCase(expectedAddress.trim())) {
            throw new IllegalStateException("KMS key " + reference + " does not control the configured wallet address "
                    + expectedAddress.trim() + " (the key's address is " + derived + ")");
        }
        return new KmsEvmSigner(service, reference, derived);
    }

    @Override
    protected ECDSASignature rawSign(byte[] digest) {
        byte[] der = service.sign(keyReference, digest);
        try {
            return fromDer(der);
        } catch (IllegalStateException e) {
            service.recordSignatureRejected("invalid_signature");
            throw e;
        }
    }

    @Override
    protected IllegalStateException invalidSignature(String detail) {
        service.recordSignatureRejected("invalid_signature");
        return super.invalidSignature(detail);
    }

    @Override
    protected IllegalStateException recoveryFailed() {
        service.recordSignatureRejected("address_mismatch");
        return new IllegalStateException("KMS signature does not recover to the wallet address " + address()
                + "; the key version no longer matches the stored wallet");
    }
}
