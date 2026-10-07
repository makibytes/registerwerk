package de.makibytes.registerwerk.wallet.internal;

import org.web3j.crypto.ECDSASignature;

import java.util.Objects;

/** secp256k1 EVM signer backed by an opaque PKCS#11 key handle. */
public final class Pkcs11EvmSigner extends RecoverableEvmSigner {

    private final Pkcs11HsmService hsm;
    private final String keyAlias;

    public Pkcs11EvmSigner(Pkcs11HsmService hsm, String keyAlias, String address) {
        super(requireText(address, "address"));
        this.hsm = Objects.requireNonNull(hsm, "hsm");
        this.keyAlias = requireText(keyAlias, "keyAlias");
    }

    @Override
    protected ECDSASignature rawSign(byte[] digest) {
        return fromRawOrDer(hsm.signDigest(keyAlias, digest));
    }

    @Override
    protected IllegalStateException recoveryFailed() {
        return new IllegalStateException("HSM signature does not recover to configured wallet " + address()
                + "; check key alias and address");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
