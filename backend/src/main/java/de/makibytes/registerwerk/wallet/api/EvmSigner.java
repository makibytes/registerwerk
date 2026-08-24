package de.makibytes.registerwerk.wallet.api;

import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;

import java.nio.charset.StandardCharsets;

/**
 * Opaque EVM signing capability owned by the wallet module.
 *
 * <p>Callers deliberately receive no key pair or private-key bytes. Implementations may use an
 * encrypted software keystore, PKCS#11, or a remote signer while transaction code stays unchanged.
 */
public interface EvmSigner {
    String address();
    byte[] signTransaction(RawTransaction transaction, long chainId);
    Sign.SignatureData signDigest(byte[] digest);

    default Sign.SignatureData signPrefixedHash(byte[] hash) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("EIP-191 claim hash must be exactly 32 bytes");
        }
        byte[] prefix = "\u0019Ethereum Signed Message:\n32".getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[prefix.length + hash.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(hash, 0, input, prefix.length, hash.length);
        return signDigest(Hash.sha3(input));
    }
}
