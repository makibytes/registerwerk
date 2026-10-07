package de.makibytes.registerwerk.wallet.internal;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory secp256k1 "KMS": holds a real key, returns DER signatures the way a KMS does (half of
 * them deliberately with a HIGH s, which Ethereum rejects unless the caller normalises) and can be
 * told to fail, stall, sign with the wrong key or return garbage.
 */
final class FakeKmsSigningClient implements KmsSigningClient {

    static final String KEY = "projects/p/locations/europe/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1";

    final ECKeyPair key;
    final AtomicInteger publicKeyCalls = new AtomicInteger();
    final AtomicInteger signCalls = new AtomicInteger();
    final AtomicInteger highSReturned = new AtomicInteger();
    final Deque<KmsSigningException> failures = new ArrayDeque<>();
    volatile Duration delay = Duration.ZERO;
    volatile ECKeyPair signWith;
    volatile byte[] garbage;
    volatile byte[] publicKeyOverride;
    volatile KmsSigningException publicKeyFailure;
    private final java.util.Random random = new java.util.Random(42);

    FakeKmsSigningClient(ECKeyPair key) {
        this.key = key;
    }

    @Override public String provider() { return "fake"; }

    @Override public boolean isValidKeyReference(String keyReference) {
        return keyReference != null && keyReference.startsWith("projects/");
    }

    @Override
    public byte[] getPublicKey(String keyReference) {
        publicKeyCalls.incrementAndGet();
        if (publicKeyFailure != null) {
            throw publicKeyFailure;
        }
        if (publicKeyOverride != null) {
            return publicKeyOverride;
        }
        byte[] xy = Numeric.toBytesPadded(key.getPublicKey(), 64);
        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(xy, 0, point, 1, 64);
        return point;
    }

    @Override
    public byte[] signDigest(String keyReference, byte[] digest) {
        signCalls.incrementAndGet();
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KmsSigningException(KmsSigningException.Kind.TRANSIENT, "interrupted");
            }
        }
        synchronized (failures) {
            KmsSigningException next = failures.poll();
            if (next != null) {
                throw next;
            }
        }
        if (garbage != null) {
            return garbage;
        }
        ECDSASignature low = (signWith != null ? signWith : key).sign(digest);
        BigInteger s = low.s;
        synchronized (random) {
            if (random.nextBoolean()) {
                s = Sign.CURVE_PARAMS.getN().subtract(s);
                highSReturned.incrementAndGet();
            }
        }
        return der(low.r, s);
    }

    static byte[] der(BigInteger r, BigInteger s) {
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(new ASN1Integer(r));
        v.add(new ASN1Integer(s));
        try {
            return new DERSequence(v).getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
