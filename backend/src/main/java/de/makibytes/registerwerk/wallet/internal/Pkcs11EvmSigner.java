package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.transaction.type.TransactionType;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/** secp256k1 EVM signer backed by an opaque PKCS#11 key handle. */
public final class Pkcs11EvmSigner implements EvmSigner {

    private final Pkcs11HsmService hsm;
    private final String keyAlias;
    private final String address;

    public Pkcs11EvmSigner(Pkcs11HsmService hsm, String keyAlias, String address) {
        this.hsm = Objects.requireNonNull(hsm, "hsm");
        this.keyAlias = requireText(keyAlias, "keyAlias");
        this.address = Keys.toChecksumAddress(requireText(address, "address"));
    }

    @Override
    public String address() {
        return address;
    }

    /**
     * Signs a legacy (EIP-155) or typed (EIP-2718, e.g. EIP-1559) transaction. web3j's
     * {@code encode(tx, chainId)} only accepts legacy transactions (it throws for type-2), so the
     * signing preimage differs: legacy appends {@code (chainId, 0, 0)} and signs with
     * {@code v = 35 + 2*chainId + yParity}; a typed transaction already carries its chain id inside
     * the payload, is prefixed with its type byte, and is written with yParity (web3j's typed
     * encoder takes the electrum {@code v = 27 + recId} that {@link #signDigest} returns and emits
     * {@code recId}).
     */
    @Override
    public byte[] signTransaction(RawTransaction transaction, long chainId) {
        boolean legacy = transaction.getType() == TransactionType.LEGACY;
        byte[] preimage = legacy
                ? TransactionEncoder.encode(transaction, chainId)
                : TransactionEncoder.encode(transaction);
        Sign.SignatureData signature = signDigest(Hash.sha3(preimage));
        Sign.SignatureData finalSignature = legacy
                ? TransactionEncoder.createEip155SignatureData(signature, chainId)
                : signature;
        return TransactionEncoder.encode(transaction, finalSignature);
    }

    @Override
    public Sign.SignatureData signDigest(byte[] digest) {
        ECDSASignature signature = decode(hsm.signDigest(keyAlias, digest)).toCanonicalised();
        for (int recoveryId = 0; recoveryId < 4; recoveryId++) {
            BigInteger publicKey = Sign.recoverFromSignature(recoveryId, signature, digest);
            if (publicKey != null) {
                String recovered = "0x" + Keys.getAddress(publicKey);
                if (recovered.equalsIgnoreCase(address)) {
                    return new Sign.SignatureData(Sign.getVFromRecId(recoveryId),
                            toBytes(signature.r), toBytes(signature.s));
                }
            }
        }
        throw new IllegalStateException("HSM signature does not recover to configured wallet " + address
                + "; check key alias and address");
    }

    private static ECDSASignature decode(byte[] encoded) {
        try {
            if (encoded.length == 64) {
                return new ECDSASignature(
                        new BigInteger(1, Arrays.copyOfRange(encoded, 0, 32)),
                        new BigInteger(1, Arrays.copyOfRange(encoded, 32, 64)));
            }
            ASN1Sequence sequence = ASN1Sequence.getInstance(encoded);
            if (sequence.size() != 2) {
                throw new IllegalArgumentException("expected two integers");
            }
            return new ECDSASignature(
                    ASN1Integer.getInstance(sequence.getObjectAt(0)).getPositiveValue(),
                    ASN1Integer.getInstance(sequence.getObjectAt(1)).getPositiveValue());
        } catch (RuntimeException e) {
            throw new IllegalStateException("HSM returned an invalid ECDSA signature", e);
        }
    }

    private static byte[] toBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, result, 32 - copy, copy);
        return result;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
