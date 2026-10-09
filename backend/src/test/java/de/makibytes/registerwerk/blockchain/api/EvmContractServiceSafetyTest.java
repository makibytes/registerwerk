package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.internal.NonceCoordinator;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.shared.TransientChainException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 K4a submission hardening: fee/gas ceilings (P4B-2), estimateGas revert vs unsupported
 * (P4B-3), pinned signing chain id (P4C-1) and the bounded immediate-submission slots (P4B-1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvmContractService - submission safety (Phase 4 K4a)")
class EvmContractServiceSafetyTest {

    private static final String CONTRACT = "0x" + "cc".repeat(20);
    private static final String FROM = "0x" + "aa".repeat(20);
    private static final UUID CHAIN_CONFIG_ID = UUID.randomUUID();
    private static final long PINNED = 11155111L;
    private static final Function PAUSE = new Function("pause", List.of(), List.of());

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Web3j web3j;
    @Mock
    private NonceCoordinator nonceCoordinator;
    @Mock
    private EvmSigner signer;
    @Mock
    private ChainQuarantinePort chainQuarantine;
    @Mock
    private ChainConfigRepository chainConfigRepository;

    private ChainConfig chainConfig = pinnedConfig(PINNED);
    private EvmContractService service;

    private static ChainConfig pinnedConfig(Long chainId) {
        ChainConfig config = new ChainConfig();
        config.setIdentifier("ETHEREUM_SEPOLIA");
        config.setChainId(chainId);
        return config;
    }

    private EvmContractService newService(EvmSubmissionSettings settings) {
        lenient().when(chainConfigRepository.findById(CHAIN_CONFIG_ID)).thenReturn(Optional.of(chainConfig));
        return new EvmContractService(null, chainConfigRepository, null, nonceCoordinator, chainQuarantine,
                null, null, null, settings);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Response<?>> Request<?, T> requestReturning(T response) throws Exception {
        Request<?, T> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Request<?, ?> requestThrowing(Exception toThrow) throws Exception {
        Request<?, ?> request = mock(Request.class);
        when(request.send()).thenThrow(toThrow);
        return request;
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    private void stubChainId(long reported) throws Exception {
        EthChainId chainId = new EthChainId();
        chainId.setResult(hex(reported));
        doReturn(requestReturning(chainId)).when(web3j).ethChainId();
        lenient().when(signer.address()).thenReturn(FROM);
    }

    private void stubFeeHistory(long baseFeeWei, long tipWei) throws Exception {
        EthFeeHistory response = new EthFeeHistory();
        response.setResult(new EthFeeHistory.FeeHistory("0x1", List.of(), List.of(hex(baseFeeWei)), List.of()));
        doReturn(requestReturning(response)).when(web3j).ethFeeHistory(eq(1), any(), any());
        EthMaxPriorityFeePerGas tip = new EthMaxPriorityFeePerGas();
        tip.setResult(hex(tipWei));
        doReturn(requestReturning(tip)).when(web3j).ethMaxPriorityFeePerGas();
    }

    private void stubEstimate(long gas) throws Exception {
        EthEstimateGas estimate = new EthEstimateGas();
        estimate.setResult(hex(gas));
        doReturn(requestReturning(estimate)).when(web3j).ethEstimateGas(any());
    }

    private void stubEstimateError(int code, String message, String data) throws Exception {
        Response.Error error = new Response.Error();
        error.setCode(code);
        error.setMessage(message);
        error.setData(data);
        EthEstimateGas estimate = new EthEstimateGas();
        estimate.setError(error);
        doReturn(requestReturning(estimate)).when(web3j).ethEstimateGas(any());
    }

    private void stubBroadcast() throws Exception {
        when(nonceCoordinator.withNonce(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            NonceCoordinator.NonceCallback<?> callback = inv.getArgument(3);
            return callback.withNonce(BigInteger.ONE);
        });
        lenient().when(signer.signTransaction(any(RawTransaction.class), anyLong())).thenReturn(new byte[]{1});
        EthSendTransaction sent = new EthSendTransaction();
        sent.setResult("0xok");
        doReturn(requestReturning(sent)).when(web3j).ethSendRawTransaction(any());
    }

    private void assertNothingSigned() {
        verify(signer, never()).signTransaction(any(), anyLong());
        verify(web3j, never()).ethSendRawTransaction(any());
    }

    // ── P4B-2 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P4B-2: a node reporting an absurd base fee never leads to a signed transaction")
    void feeAboveDefaultCeilingIsRefusedBeforeSigning() throws Exception {
        stubChainId(PINNED);
        stubFeeHistory(1_000_000L * 1_000_000_000L /* 10^6 gwei */, 2_000_000_000L);
        stubEstimate(100_000L);
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(FeeAboveCeilingException.class)
                .hasMessageContaining("maxFeePerGas");
        assertNothingSigned();
    }

    @Test
    @DisplayName("P4B-2: a tip above the 50 gwei default is refused; a chain_config override replaces the default")
    void tipCeilingAndPerChainOverride() throws Exception {
        stubChainId(PINNED);
        stubFeeHistory(1_000_000_000L, 60_000_000_000L /* 60 gwei tip */);
        stubEstimate(100_000L);
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(FeeAboveCeilingException.class)
                .hasMessageContaining("maxPriorityFeePerGas");
        assertNothingSigned();

        chainConfig.setMaxPriorityFeePerGasWei(BigInteger.valueOf(100_000_000_000L));
        chainConfig.setMaxFeePerGasWei(BigInteger.valueOf(200_000_000_000L));
        stubBroadcast();
        assertThat(service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE)).isEqualTo("0xok");
    }

    @Test
    @DisplayName("P4B-2: legacy gasPrice above the ceiling and a gas limit above the block cap are refused")
    void legacyGasPriceAndGasLimitCeilings() throws Exception {
        stubChainId(PINNED);
        doReturn(requestThrowing(new RuntimeException("the method eth_feeHistory does not exist")))
                .when(web3j).ethFeeHistory(eq(1), any(), any());
        EthGasPrice gasPrice = new EthGasPrice();
        gasPrice.setResult(hex(600_000_000_000L)); // 600 gwei * 1.2 = 720 gwei > 500
        doReturn(requestReturning(gasPrice)).when(web3j).ethGasPrice();
        stubEstimate(100_000L);
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(FeeAboveCeilingException.class).hasMessageContaining("gasPrice");

        stubEstimate(51_000_000L); // 51M * 1.2 = 61.2M > 60M
        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(FeeAboveCeilingException.class).hasMessageContaining("gasLimit");
        assertNothingSigned();
    }

    // ── P4B-3 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P4B-3: an execution-reverted estimate fails before signing with the reason")
    void estimateRevertFailsBeforeSigning() throws Exception {
        stubChainId(PINNED);
        stubEstimateError(3, "execution reverted: paused", null);
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT,
                "0xdeadbeef", BigInteger.valueOf(321_000L)))
                .isInstanceOf(ChainRevertException.class)
                .hasMessageContaining("paused");
        assertNothingSigned();
    }

    @Test
    @DisplayName("P4B-3: Error(string) revert data is decoded; custom errors keep their selector")
    void estimateRevertDataIsDecoded() {
        Response.Error error = new Response.Error();
        error.setCode(3);
        error.setMessage("execution reverted");
        error.setData("0x08c379a0"
                + "0000000000000000000000000000000000000000000000000000000000000020"
                + "0000000000000000000000000000000000000000000000000000000000000006"
                + "66726f7a656e0000000000000000000000000000000000000000000000000000");
        assertThat(EvmContractService.decodeRevertReason(error)).isEqualTo("frozen");

        error.setData("0x1234abcd" + "00".repeat(32));
        assertThat(EvmContractService.decodeRevertReason(error)).contains("custom error 0x1234abcd");
    }

    @Test
    @DisplayName("P4B-3: a revert is detected from the data even when the node message is generic")
    void estimateRevertDetectedByData() throws Exception {
        stubChainId(PINNED);
        stubEstimateError(-32000, "gas estimation failed",
                "0x08c379a0" + "0000000000000000000000000000000000000000000000000000000000000020"
                        + "0000000000000000000000000000000000000000000000000000000000000004"
                        + "6e6f706500000000000000000000000000000000000000000000000000000000");
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(ChainRevertException.class).hasMessageContaining("nope");
        assertNothingSigned();
    }

    @Test
    @DisplayName("P4B-3: method-not-found keeps the fallback gas limit; other RPC rejections do not sign")
    void estimateUnsupportedFallsBackButOtherErrorsFail() throws Exception {
        stubChainId(PINNED);
        stubFeeHistory(1_000_000_000L, 1_000_000_000L);
        stubEstimateError(-32601, "Method eth_estimateGas not found", null);
        stubBroadcast();
        service = newService(EvmSubmissionSettings.defaults());

        assertThat(service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE)).isEqualTo("0xok");
        ArgumentCaptor<RawTransaction> tx = ArgumentCaptor.forClass(RawTransaction.class);
        verify(signer).signTransaction(tx.capture(), eq(PINNED));
        assertThat(tx.getValue().getGasLimit()).isEqualTo(BigInteger.valueOf(1_500_000L));

        stubEstimateError(-32000, "insufficient funds for transfer", null);
        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(TransientChainException.class);
    }

    // ── P4C-1 (signing half) ──────────────────────────────────────────────────

    @Test
    @DisplayName("P4C-1: a node answering another chain id than the pinned one is refused")
    void nodeChainIdMismatchIsRefused() throws Exception {
        stubChainId(1L); // node claims mainnet, chain is pinned to Sepolia
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(ChainIdMismatchException.class)
                .hasMessageContaining("pinned chain id is " + PINNED);
        assertNothingSigned();
    }

    @Test
    @DisplayName("P4C-1: an unpinned chain is refused - no default of 1, no chain id learned from the node")
    void unpinnedChainIdIsRefused() {
        chainConfig = pinnedConfig(null);
        service = newService(EvmSubmissionSettings.defaults());

        assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                .isInstanceOf(ChainIdMismatchException.class).hasMessageContaining("no pinned chain_id");
        assertThatThrownBy(() -> service.chainId(CHAIN_CONFIG_ID)).isInstanceOf(ChainIdMismatchException.class);
        verify(web3j, never()).ethChainId();
        assertNothingSigned();
    }

    // ── P4B-1 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P4B-1: immediate paths are bounded; excess callers fail fast (transient) and the slot is released")
    void immediateSubmissionsAreBoundedBySemaphore() throws Exception {
        stubChainId(PINNED);
        stubFeeHistory(1_000_000_000L, 1_000_000_000L);
        stubEstimate(100_000L);
        CountDownLatch insideCoordinator = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(nonceCoordinator.withNonce(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            NonceCoordinator.NonceCallback<?> callback = inv.getArgument(3);
            insideCoordinator.countDown();
            release.await();
            return callback.withNonce(BigInteger.ONE);
        });
        lenient().when(signer.signTransaction(any(RawTransaction.class), anyLong())).thenReturn(new byte[]{1});
        EthSendTransaction sent = new EthSendTransaction();
        sent.setResult("0xok");
        doReturn(requestReturning(sent)).when(web3j).ethSendRawTransaction(any());
        service = newService(new EvmSubmissionSettings(1, 20, 150, 500, 50, 30_000_000)); // 1 permit, 150 ms

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE));
            assertThat(insideCoordinator.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE))
                    .isInstanceOf(TransientChainException.class).hasMessageContaining("slots are busy");

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("0xok");
            assertThat(service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE)).isEqualTo("0xok");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("P4B-1: the immediate slot is acquired BEFORE the transaction (and its pooled connection) begins")
    void permitIsAcquiredBeforeTheTransactionBegins() throws Exception {
        stubChainId(PINNED);
        stubFeeHistory(1_000_000_000L, 1_000_000_000L);
        stubEstimate(100_000L);
        when(nonceCoordinator.withNonce(anyLong(), any(), any(), any())).thenAnswer(inv ->
                ((NonceCoordinator.NonceCallback<?>) inv.getArgument(3)).withNonce(BigInteger.ONE));
        lenient().when(signer.signTransaction(any(RawTransaction.class), anyLong())).thenReturn(new byte[]{1});
        EthSendTransaction sent = new EthSendTransaction();
        sent.setResult("0xok");
        doReturn(requestReturning(sent)).when(web3j).ethSendRawTransaction(any());
        service = newService(new EvmSubmissionSettings(2, 20, 150, 500, 50, 30_000_000));
        java.util.concurrent.Semaphore slots = (java.util.concurrent.Semaphore)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "immediateSlots");
        int[] permitsWhenTransactionBegan = {-1};
        var txManager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) {
                permitsWhenTransactionBegan[0] = slots.availablePermits();
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
        };
        service.setTransactionManager(txManager);

        assertThat(service.submit(CHAIN_CONFIG_ID, web3j, signer, CONTRACT, PAUSE)).isEqualTo("0xok");

        assertThat(permitsWhenTransactionBegan[0]).as("one of two permits already held when the tx began").isEqualTo(1);
        assertThat(slots.availablePermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("P4B-1: pool of 20 gives 5 slots by default; explicit configuration wins; never below 1")
    void permitDerivation() {
        assertThat(EvmSubmissionSettings.defaults().immediateSubmitPermits()).isEqualTo(5);
        assertThat(new EvmSubmissionSettings(3, 20, 1, 500, 50, 1).immediateSubmitPermits()).isEqualTo(3);
        assertThat(new EvmSubmissionSettings(0, 2, 1, 500, 50, 1).immediateSubmitPermits()).isEqualTo(1);
    }
}
