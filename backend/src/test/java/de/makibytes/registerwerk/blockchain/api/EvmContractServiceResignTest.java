package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthFeeHistory;
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P4B-4: same-nonce replacement signing (re-price and cancel) with real signatures. */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvmContractService.resign - same-nonce replacement (P4B-4)")
class EvmContractServiceResignTest {

    private static final UUID CHAIN_CONFIG_ID = UUID.randomUUID();
    private static final long CHAIN = 11155111L;
    private static final String CONTRACT = "0x" + "cc".repeat(20);
    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Web3j web3j;
    @Mock private de.makibytes.registerwerk.blockchain.internal.NonceCoordinator nonceCoordinator;
    @Mock private ChainQuarantinePort chainQuarantine;
    @Mock private ChainConfigRepository chainConfigRepository;

    private final Credentials credentials = Credentials.create(
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318");
    private final EvmSigner signer = new EvmSigner() {
        @Override public String address() { return credentials.getAddress(); }
        @Override public byte[] signTransaction(RawTransaction tx, long chainId) {
            return TransactionEncoder.signMessage(tx, chainId, credentials);
        }
        @Override public Sign.SignatureData signDigest(byte[] digest) { throw new UnsupportedOperationException(); }
    };
    private EvmContractService service;

    @BeforeEach
    void setUp() throws Exception {
        ChainConfig config = new ChainConfig();
        config.setIdentifier("ETHEREUM_SEPOLIA");
        config.setChainId(CHAIN);
        lenient().when(chainConfigRepository.findById(CHAIN_CONFIG_ID)).thenReturn(Optional.of(config));
        service = new EvmContractService(null, chainConfigRepository, null, nonceCoordinator, chainQuarantine,
                null, null, null, EvmSubmissionSettings.defaults());
        EthChainId chainId = new EthChainId();
        chainId.setResult("0x" + Long.toHexString(CHAIN));
        doReturn(request(chainId)).when(web3j).ethChainId();
        // network is calm: base fee 10 gwei, tip 1 gwei -> a fresh maxFee of 21 gwei
        EthFeeHistory history = new EthFeeHistory();
        history.setResult(new EthFeeHistory.FeeHistory("0x1", List.of(),
                List.of("0x" + GWEI.multiply(BigInteger.TEN).toString(16)), List.of()));
        doReturn(request(history)).when(web3j).ethFeeHistory(eq(1), any(), any());
        EthMaxPriorityFeePerGas tip = new EthMaxPriorityFeePerGas();
        tip.setResult("0x" + GWEI.toString(16));
        doReturn(request(tip)).when(web3j).ethMaxPriorityFeePerGas();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Response<?>> Request<?, T> request(T response) throws Exception {
        Request<?, T> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    private String original(BigInteger maxFee, BigInteger tip) {
        RawTransaction tx = RawTransaction.createTransaction(CHAIN, BigInteger.valueOf(7), BigInteger.valueOf(90_000),
                CONTRACT, BigInteger.ZERO, "0x1234abcd", tip, maxFee);
        return Numeric.toHexString(TransactionEncoder.signMessage(tx, CHAIN, credentials));
    }

    @Test
    @DisplayName("re-price keeps nonce, recipient and calldata and raises both fees by at least 15 %")
    void repriceKeepsTheCallAndBumpsFees() {
        String payload = original(GWEI.multiply(BigInteger.valueOf(100)), GWEI.multiply(BigInteger.TWO));

        var replacement = service.resign(CHAIN_CONFIG_ID, web3j, signer, payload,
                EvmContractService.ReplacementKind.REPRICE, 15);

        RawTransaction decoded = TransactionDecoder.decode(replacement.signedPayload());
        assertThat(decoded.getNonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(decoded.getTo()).isEqualToIgnoringCase(CONTRACT);
        assertThat(decoded.getData()).isEqualTo("1234abcd");
        assertThat(decoded.getGasLimit()).isEqualTo(BigInteger.valueOf(90_000));
        var info = EvmContractService.describe(replacement.signedPayload());
        assertThat(info.maxFeePerGas()).isGreaterThanOrEqualTo(GWEI.multiply(BigInteger.valueOf(115)));
        assertThat(info.maxPriorityFeePerGas()).isGreaterThanOrEqualTo(GWEI.multiply(BigInteger.TWO)
                .multiply(BigInteger.valueOf(115)).divide(BigInteger.valueOf(100)));
        assertThat(replacement.nonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(replacement.txHash()).isEqualTo(Numeric.toHexString(Hash.sha3(
                Numeric.hexStringToByteArray(replacement.signedPayload()))));
        assertThat(replacement.senderAddress()).isEqualToIgnoringCase(credentials.getAddress());
    }

    @Test
    @DisplayName("a fee that is already above the network is still bumped past the old one (10 % node rule)")
    void bumpNeverFallsBelowTheReplacementRule() {
        BigInteger oldMax = GWEI.multiply(BigInteger.valueOf(30)).add(BigInteger.valueOf(7));
        String payload = original(oldMax, GWEI.add(BigInteger.valueOf(3)));

        var replacement = service.resign(CHAIN_CONFIG_ID, web3j, signer, payload,
                EvmContractService.ReplacementKind.REPRICE, 15);

        var info = EvmContractService.describe(replacement.signedPayload());
        assertThat(info.maxFeePerGas()).isGreaterThanOrEqualTo(oldMax.multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100)));
        assertThat(info.maxPriorityFeePerGas()).isGreaterThanOrEqualTo(
                GWEI.add(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100)));
    }

    @Test
    @DisplayName("cancel is a 0-value 21000-gas self-send at the same nonce")
    void cancelIsAZeroValueSelfSend() {
        String payload = original(GWEI.multiply(BigInteger.valueOf(100)), GWEI.multiply(BigInteger.TWO));

        var cancel = service.resign(CHAIN_CONFIG_ID, web3j, signer, payload,
                EvmContractService.ReplacementKind.CANCEL, 15);

        RawTransaction decoded = TransactionDecoder.decode(cancel.signedPayload());
        assertThat(decoded.getNonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(decoded.getTo()).isEqualToIgnoringCase(credentials.getAddress());
        assertThat(decoded.getValue()).isEqualTo(BigInteger.ZERO);
        assertThat(decoded.getGasLimit()).isEqualTo(BigInteger.valueOf(21_000));
        assertThat(decoded.getData()).isNullOrEmpty();
    }

    @Test
    @DisplayName("a replacement above the chain's fee ceiling is refused, never signed")
    void replacementAboveTheCeilingIsRefused() {
        // default cap is 500 gwei; 480 gwei + 15 % = 552 gwei
        String payload = original(GWEI.multiply(BigInteger.valueOf(480)), GWEI.multiply(BigInteger.TWO));

        assertThatThrownBy(() -> service.resign(CHAIN_CONFIG_ID, web3j, signer, payload,
                EvmContractService.ReplacementKind.REPRICE, 15))
                .isInstanceOf(FeeAboveCeilingException.class);
    }
}
