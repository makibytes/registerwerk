package de.makibytes.registerwerk.wallet.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.transaction.type.Transaction1559;
import org.web3j.crypto.transaction.type.TransactionType;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The PKCS#11 signer must produce valid signed payloads for both legacy (EIP-155) and typed
 * (EIP-1559) transactions. web3j 6's {@code TransactionEncoder.encode(tx, chainId)} throws for
 * type-2, which made the HSM path unable to sign any EIP-1559 transaction (P4D-1). The HSM is
 * simulated by a mock that signs the digest with a software key and returns raw {@code r||s}.
 */
@DisplayName("Pkcs11EvmSigner - legacy and type-2 payloads")
class Pkcs11EvmSignerTest {

    private static final long CHAIN_ID = 137L;
    private static final String TO = "0x" + "cc".repeat(20);

    private record Fixture(Pkcs11EvmSigner signer, Credentials software) {}

    private static Fixture fixture() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        Credentials software = Credentials.create(keyPair);
        Pkcs11HsmService hsm = mock(Pkcs11HsmService.class);
        when(hsm.signDigest(eq("alias"), any())).thenAnswer(inv -> {
            byte[] digest = inv.getArgument(1);
            Sign.SignatureData sig = Sign.signMessage(digest, keyPair, false);
            byte[] raw = new byte[64];
            System.arraycopy(sig.getR(), 0, raw, 0, 32);
            System.arraycopy(sig.getS(), 0, raw, 32, 32);
            return raw;
        });
        return new Fixture(new Pkcs11EvmSigner(hsm, "alias", software.getAddress()), software);
    }

    private static RawTransaction legacy(long nonce) {
        return RawTransaction.createTransaction(BigInteger.valueOf(nonce), BigInteger.valueOf(20_000_000_000L),
                BigInteger.valueOf(100_000), TO, "0xdeadbeef");
    }

    private static RawTransaction type2(long nonce) {
        return RawTransaction.createTransaction(CHAIN_ID, BigInteger.valueOf(nonce), BigInteger.valueOf(100_000),
                TO, BigInteger.ZERO, "0xdeadbeef", BigInteger.valueOf(2_000_000_000L),
                BigInteger.valueOf(60_000_000_000L));
    }

    @Test
    @DisplayName("type-2 transaction: signs (no CryptoWeb3jException), recovers to the HSM address, "
            + "matches web3j's software signer byte for byte")
    void signsEip1559Transaction() throws Exception {
        Fixture f = fixture();
        boolean sawParity0 = false;
        boolean sawParity1 = false;
        for (long nonce = 0; nonce < 24; nonce++) {
            RawTransaction tx = type2(nonce);
            assertThat(tx.getType()).isEqualTo(TransactionType.EIP1559);

            byte[] signed = f.signer().signTransaction(tx, CHAIN_ID);

            assertThat(signed[0]).isEqualTo((byte) 0x02);
            SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(Numeric.toHexString(signed));
            assertThat(decoded.getFrom()).isEqualToIgnoringCase(f.software().getAddress());
            assertThat(((Transaction1559) decoded.getTransaction()).getChainId()).isEqualTo(CHAIN_ID);
            assertThat(decoded.getNonce()).isEqualTo(BigInteger.valueOf(nonce));
            assertThat(signed).isEqualTo(TransactionEncoder.signMessage(tx, CHAIN_ID, f.software()));
            byte[] v = decoded.getSignatureData().getV();
            // The decoder reports the electrum form (27 + yParity); the wire bytes themselves are
            // pinned by the byte-for-byte comparison with web3j's own signer above.
            int yParity = v[v.length - 1] - 27;
            assertThat(yParity).isIn(0, 1);
            sawParity0 |= yParity == 0;
            sawParity1 |= yParity == 1;
        }
        assertThat(sawParity0 && sawParity1).as("both yParity values exercised").isTrue();
    }

    @Test
    @DisplayName("legacy transaction: EIP-155 v, recovers to the HSM address, matches web3j's software signer")
    void signsLegacyTransaction() throws Exception {
        Fixture f = fixture();
        for (long nonce = 0; nonce < 24; nonce++) {
            RawTransaction tx = legacy(nonce);
            assertThat(tx.getType()).isEqualTo(TransactionType.LEGACY);

            byte[] signed = f.signer().signTransaction(tx, CHAIN_ID);

            SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(Numeric.toHexString(signed));
            assertThat(decoded.getFrom()).isEqualToIgnoringCase(f.software().getAddress());
            assertThat(decoded.getChainId()).isEqualTo(CHAIN_ID);
            assertThat(signed).isEqualTo(TransactionEncoder.signMessage(tx, CHAIN_ID, f.software()));
        }
    }
}
