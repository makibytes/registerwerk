package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.transaction.type.TransactionType;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Shared EVM signing logic for signers whose private key is opaque (PKCS#11 HSM, cloud KMS): the
 * custody backend only returns an ECDSA {@code (r, s)} for a 32-byte digest, everything Ethereum
 * specific lives here - range checks, low-s normalisation (EIP-2), recovery-id selection against the
 * expected address (fail closed when neither parity matches), and legacy (EIP-155) / typed (EIP-2718)
 * transaction encoding.
 */
public abstract class RecoverableEvmSigner implements EvmSigner {

    private final String address;

    protected RecoverableEvmSigner(String address) {
        this.address = Keys.toChecksumAddress(address);
    }

    @Override
    public final String address() {
        return address;
    }

    /** Asks the custody backend to sign {@code digest}; returns the raw (un-normalised) signature. */
    protected abstract ECDSASignature rawSign(byte[] digest);

    /** Raised when the backend returns a signature that cannot be valid (r or s outside [1, n-1]). */
    protected IllegalStateException invalidSignature(String detail) {
        return new IllegalStateException("custody backend returned an " + detail + " ECDSA signature");
    }

    /** Raised when the backend's signature does not recover to {@link #address()}. */
    protected IllegalStateException recoveryFailed() {
        return new IllegalStateException("signature does not recover to configured wallet " + address
                + "; check key reference and address");
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
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("EVM digest must be exactly 32 bytes");
        }
        ECDSASignature raw = rawSign(digest);
        BigInteger n = Sign.CURVE_PARAMS.getN();
        if (raw == null || raw.r.signum() <= 0 || raw.r.compareTo(n) >= 0
                || raw.s.signum() <= 0 || raw.s.compareTo(n) >= 0) {
            throw invalidSignature("out-of-range");
        }
        ECDSASignature signature = raw.toCanonicalised();
        // Ethereum's ecrecover only knows v = 27/28 (recId 0/1); ids 2/3 (r overflow) cannot be encoded.
        for (int recoveryId = 0; recoveryId < 2; recoveryId++) {
            BigInteger publicKey = Sign.recoverFromSignature(recoveryId, signature, digest);
            if (publicKey != null && ("0x" + Keys.getAddress(publicKey)).equalsIgnoreCase(address)) {
                return new Sign.SignatureData(Sign.getVFromRecId(recoveryId),
                        toBytes(signature.r), toBytes(signature.s));
            }
        }
        throw recoveryFailed();
    }

    /** Parses a strict ASN.1 DER {@code SEQUENCE { INTEGER r, INTEGER s }}. */
    protected static ECDSASignature fromDer(byte[] der) {
        try (ASN1InputStream in = new ASN1InputStream(der)) {
            ASN1Primitive parsed = in.readObject();
            if (in.readObject() != null || !(parsed instanceof ASN1Sequence sequence) || sequence.size() != 2) {
                throw new IllegalArgumentException("expected a single sequence of two integers");
            }
            ASN1Encodable r = sequence.getObjectAt(0);
            ASN1Encodable s = sequence.getObjectAt(1);
            return new ECDSASignature(
                    ASN1Integer.getInstance(r).getPositiveValue(),
                    ASN1Integer.getInstance(s).getPositiveValue());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("custody backend returned an invalid ECDSA signature", e);
        }
    }

    /** PKCS#11 tokens return either raw {@code r||s} (64 bytes) or DER depending on the mechanism. */
    protected static ECDSASignature fromRawOrDer(byte[] encoded) {
        if (encoded != null && encoded.length == 64) {
            return new ECDSASignature(
                    new BigInteger(1, Arrays.copyOfRange(encoded, 0, 32)),
                    new BigInteger(1, Arrays.copyOfRange(encoded, 32, 64)));
        }
        return fromDer(encoded);
    }

    private static byte[] toBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, result, 32 - copy, copy);
        return result;
    }
}
