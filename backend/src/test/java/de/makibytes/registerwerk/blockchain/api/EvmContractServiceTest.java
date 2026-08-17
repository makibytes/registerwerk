package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.internal.NonceCoordinator;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.transaction.type.Transaction1559;
import org.web3j.crypto.transaction.type.TransactionType;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthFeeHistory;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3: EIP-1559 fee resolution and {@code eth_estimateGas}-based gas limits, replacing the
 * previous fixed {@code CALL_GAS_LIMIT}/legacy-{@code gasPrice} behavior. Exercises
 * {@code EvmContractService.submit} end-to-end with a mocked {@link Web3j} and a
 * {@link NonceCoordinator} stub that invokes its callback immediately (mirroring what the real
 * Postgres-advisory-lock-backed implementation does, just without a database), so the resulting
 * {@link RawTransaction} passed to the signer can be inspected directly.
 *
 * <p>Uses {@code doReturn(...).when(mock).method(...)} throughout rather than
 * {@code when(mock.method()).thenReturn(...)}: every stubbed method here returns a nested
 * {@code Request} mock that itself needs its own {@code when(request.send())...} stub built
 * first, and building that nested mock as an argument to an outer {@code when(mock.method())}
 * call interleaves two stub recordings on Mockito's thread-local "last invocation" state, which
 * breaks the outer one. {@code doReturn(...).when(mock).method(...)} calls the mock method
 * as part of evaluating {@code .when(mock)} itself, after the return value is already fully
 * built, so there is no interleaving.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvmContractService — EIP-1559 fees and eth_estimateGas")
class EvmContractServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Web3j web3j;

    @Mock
    private NonceCoordinator nonceCoordinator;

    @Mock
    private EvmSigner signer;

    private EvmContractService service;

    private static final String CONTRACT = "0x" + "cc".repeat(20);
    private static final String FROM = "0x" + "aa".repeat(20);

    private void stubCommon() throws Exception {
        when(signer.address()).thenReturn(FROM);
        doReturn(requestReturning(chainIdResponse(11155111L))).when(web3j).ethChainId();
        // NonceCoordinator stub: invoke the callback with a fixed nonce immediately, the same
        // shape as the real advisory-lock-backed implementation from the caller's perspective.
        when(nonceCoordinator.withNonce(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            NonceCoordinator.NonceCallback<?> callback = inv.getArgument(3);
            return callback.withNonce(BigInteger.valueOf(7));
        });
        when(signer.signTransaction(any(RawTransaction.class), anyLong())).thenReturn(new byte[]{1, 2, 3});
        EthSendTransaction sent = new EthSendTransaction();
        sent.setResult("0xtxhash");
        doReturn(requestReturning(sent)).when(web3j).ethSendRawTransaction(any());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Response<?>> Request<?, T> requestReturning(T response) throws Exception {
        Request<?, T> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    private static EthChainId chainIdResponse(long chainId) {
        EthChainId r = new EthChainId();
        r.setResult(toHex(chainId));
        return r;
    }

    private static String toHex(long value) {
        return "0x" + Long.toHexString(value);
    }

    @Test
    @DisplayName("resolves EIP-1559 fees from eth_feeHistory/eth_maxPriorityFeePerGas and builds a "
            + "type-2 transaction with 2x-base-fee + tip headroom")
    void submit_buildsEip1559TransactionWhenFeeHistoryAvailable() throws Exception {
        stubCommon();

        EthFeeHistory.FeeHistory feeHistory = new EthFeeHistory.FeeHistory(
                "0x1", List.of(), List.of("0x3b9aca00" /* 1 gwei */), List.of());
        EthFeeHistory feeHistoryResponse = new EthFeeHistory();
        feeHistoryResponse.setResult(feeHistory);
        doReturn(requestReturning(feeHistoryResponse)).when(web3j).ethFeeHistory(eq(1), any(), any());

        EthMaxPriorityFeePerGas tipResponse = new EthMaxPriorityFeePerGas();
        tipResponse.setResult(toHex(2_000_000_000L)); // 2 gwei tip
        doReturn(requestReturning(tipResponse)).when(web3j).ethMaxPriorityFeePerGas();

        EthEstimateGas estimateResponse = new EthEstimateGas();
        estimateResponse.setResult(toHex(100_000L));
        doReturn(requestReturning(estimateResponse)).when(web3j).ethEstimateGas(any());

        Function function = new Function("pause", List.of(), List.of());
        service = new EvmContractService(null, null, null, nonceCoordinator);
        String txHash = service.submit(web3j, signer, CONTRACT, function);

        assertThat(txHash).isEqualTo("0xtxhash");

        ArgumentCaptor<RawTransaction> txCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(signer).signTransaction(txCaptor.capture(), eq(11155111L));
        RawTransaction built = txCaptor.getValue();

        assertThat(built.getType()).isEqualTo(TransactionType.EIP1559);
        assertThat(built.getNonce()).isEqualTo(BigInteger.valueOf(7));
        // 100,000 * 1.2 = 120,000 (the 20% eth_estimateGas safety margin).
        assertThat(built.getGasLimit()).isEqualTo(BigInteger.valueOf(120_000L));

        Transaction1559 tx1559 = (Transaction1559) built.getTransaction();
        assertThat(tx1559.getMaxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(2_000_000_000L));
        // maxFeePerGas = 2 * baseFee(1 gwei) + tip(2 gwei) = 4 gwei.
        assertThat(tx1559.getMaxFeePerGas()).isEqualTo(BigInteger.valueOf(4_000_000_000L));
    }

    @Test
    @DisplayName("falls back to a legacy transaction and the caller-supplied gas limit when "
            + "eth_feeHistory / eth_estimateGas are unavailable")
    void submit_fallsBackToLegacyFeesAndCallerGasLimit_whenRpcMethodsUnsupported() throws Exception {
        stubCommon();

        doReturn(requestThrowing(new RuntimeException("the method eth_feeHistory does not exist")))
                .when(web3j).ethFeeHistory(eq(1), any(), any());
        doReturn(requestThrowing(new RuntimeException("the method eth_estimateGas does not exist")))
                .when(web3j).ethEstimateGas(any());

        EthGasPrice gasPriceResponse = new EthGasPrice();
        gasPriceResponse.setResult(toHex(10_000_000_000L)); // 10 gwei
        doReturn(requestReturning(gasPriceResponse)).when(web3j).ethGasPrice();

        service = new EvmContractService(null, null, null, nonceCoordinator);
        BigInteger callerSuppliedGasLimit = BigInteger.valueOf(321_000L);
        String txHash = service.submit(web3j, signer, CONTRACT, "0xdeadbeef", callerSuppliedGasLimit);

        assertThat(txHash).isEqualTo("0xtxhash");

        ArgumentCaptor<RawTransaction> txCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(signer).signTransaction(txCaptor.capture(), eq(11155111L));
        RawTransaction built = txCaptor.getValue();

        assertThat(built.getType()).isEqualTo(TransactionType.LEGACY);
        assertThat(built.getGasLimit()).isEqualTo(callerSuppliedGasLimit);
        // 10 gwei * 1.2 (gasPrice()'s own existing 20% tip heuristic) = 12 gwei.
        assertThat(built.getGasPrice()).isEqualTo(BigInteger.valueOf(12_000_000_000L));
    }

    @SuppressWarnings("unchecked")
    private static Request<?, ?> requestThrowing(Exception toThrow) throws Exception {
        Request<?, ?> request = mock(Request.class);
        when(request.send()).thenThrow(toThrow);
        return request;
    }
}
