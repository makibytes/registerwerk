package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;

import java.util.Objects;

/** Software-keystore implementation retained for development and migration. */
public final class SoftwareEvmSigner implements EvmSigner {

    private final Credentials credentials;

    public SoftwareEvmSigner(Credentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public String address() {
        return credentials.getAddress();
    }

    @Override
    public byte[] signTransaction(RawTransaction transaction, long chainId) {
        return TransactionEncoder.signMessage(transaction, chainId, credentials);
    }

    @Override
    public Sign.SignatureData signDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("EVM digest must be exactly 32 bytes");
        }
        return Sign.signMessage(digest, credentials.getEcKeyPair(), false);
    }
}
