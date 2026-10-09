package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialForcedOpOutcomeEvent;
import de.makibytes.registerwerk.blockchain.events.ConfidentialForcedOpOutcomeEvent.Outcome;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfidentialForcedOpVerifier — a confidential forced op that moved 0 is never recorded as executed (T1-16)")
class ConfidentialForcedOpVerifierTest {

    private static final String TOKEN = "0x00000000000000000000000000000000000000c0";
    private static final String FROM = "0x00000000000000000000000000000000000000a1";
    private static final String TO = "0x00000000000000000000000000000000000000b2";
    private static final String TX_HASH = "0x" + "ab".repeat(32);
    private static final BigInteger HANDLE = new BigInteger("123456789abcdef", 16);

    @Mock private BlockchainTransactionRepository txRepository;
    @Mock private EvmContractService evmContractService;
    @Mock private ZamaRelayerClient zamaRelayerClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IsolatedTransactionExecutor isolatedTransactions;

    private ConfidentialForcedOpVerifier verifier;
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        verifier = new ConfidentialForcedOpVerifier(
                txRepository, evmContractService, zamaRelayerClient, eventPublisher, isolatedTransactions);
        lenient().doAnswer(inv -> {
            inv.getArgument(0, IsolatedTransactionExecutor.Work.class).run();
            return null;
        }).when(isolatedTransactions).run(any());
        lenient().when(zamaRelayerClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("event topics match the Solidity signatures (euint64 is bytes32 in the ABI since fhevm-solidity 0.14)")
    void topicsMatchContractEvents() {
        assertThat(ConfidentialForcedOpVerifier.TRANSFER_TOPIC)
                .isEqualTo(Hash.sha3String("ConfidentialTransfer(address,address,bytes32)"));
        assertThat(ConfidentialForcedOpVerifier.BURN_TOPIC)
                .isEqualTo(Hash.sha3String("ConfidentialBurn(address,bytes32)"));
    }

    @Test
    @DisplayName("forced transfer whose moved-amount handle decrypts to 0 → NOT_EXECUTED_INSUFFICIENT_BALANCE")
    void forcedTransferMovedZero_isNotExecuted() throws Exception {
        BlockchainTransaction tx = tx(ConfidentialForcedOpVerifier.FORCED_TRANSFER,
                Map.of("from", FROM, "to", TO, "amount", "1000", "legalBasis", "court order"));
        stubReceipt(transferLog(FROM, TO, HANDLE));
        when(zamaRelayerClient.requestOperatorDecrypt(eq(Numeric.toHexStringWithPrefixZeroPadded(HANDLE, 64)), eq(TOKEN)))
                .thenReturn(BigInteger.ZERO);

        verifier.verifyPending();

        assertThat(tx.getExecutionOutcome()).isEqualTo(Outcome.NOT_EXECUTED_INSUFFICIENT_BALANCE.name());
        ConfidentialForcedOpOutcomeEvent event = publishedEvent();
        assertThat(event.outcome()).isEqualTo(Outcome.NOT_EXECUTED_INSUFFICIENT_BALANCE);
        assertThat(event.orderedAmount()).isEqualTo("1000");
        assertThat(event.decryptedAmount()).isEqualTo("0");
        assertThat(event.txId()).isEqualTo(tx.getId());
    }

    @Test
    @DisplayName("forced transfer whose handle decrypts to the ordered amount → EXECUTED")
    void forcedTransferMovedFullAmount_isExecuted() throws Exception {
        BlockchainTransaction tx = tx(ConfidentialForcedOpVerifier.FORCED_TRANSFER,
                Map.of("from", FROM, "to", TO, "amount", "1000", "legalBasis", "court order"));
        stubReceipt(transferLog(FROM, TO, HANDLE));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(TOKEN))).thenReturn(BigInteger.valueOf(1000));

        verifier.verifyPending();

        assertThat(tx.getExecutionOutcome()).isEqualTo(Outcome.EXECUTED.name());
        assertThat(publishedEvent().outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @Test
    @DisplayName("forced burn that moved 0 → NOT_EXECUTED_INSUFFICIENT_BALANCE")
    void forceBurnMovedZero_isNotExecuted() throws Exception {
        BlockchainTransaction tx = tx(ConfidentialForcedOpVerifier.FORCE_BURN,
                Map.of("from", FROM, "amount", "50", "legalBasis", "§26"));
        stubReceipt(burnLog(FROM, HANDLE));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), eq(TOKEN))).thenReturn(BigInteger.ZERO);

        verifier.verifyPending();

        assertThat(tx.getExecutionOutcome()).isEqualTo(Outcome.NOT_EXECUTED_INSUFFICIENT_BALANCE.name());
    }

    @Test
    @DisplayName("receipt without a matching event from the token → UNVERIFIED_INCONSISTENT, never EXECUTED")
    void missingEvent_isInconsistent() throws Exception {
        BlockchainTransaction tx = tx(ConfidentialForcedOpVerifier.FORCED_TRANSFER,
                Map.of("from", FROM, "to", TO, "amount", "1000"));
        // Log for a different holder pair — must not be matched.
        stubReceipt(transferLog(TO, FROM, HANDLE));

        verifier.verifyPending();

        assertThat(tx.getExecutionOutcome()).isEqualTo(Outcome.UNVERIFIED_INCONSISTENT.name());
        verify(zamaRelayerClient, never()).requestOperatorDecrypt(anyString(), anyString());
    }

    @Test
    @DisplayName("decrypt failure is retried with backoff and closed as UNVERIFIED_DECRYPT_FAILED at the budget")
    void decryptFailure_retriesThenUnverified() throws Exception {
        BlockchainTransaction tx = tx(ConfidentialForcedOpVerifier.FORCED_TRANSFER,
                Map.of("from", FROM, "to", TO, "amount", "1000"));
        stubReceipt(transferLog(FROM, TO, HANDLE));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenThrow(new IllegalStateException("KMS unavailable"));

        verifier.verifyPending();
        assertThat(tx.getExecutionOutcome()).isNull();
        assertThat(tx.getExecutionOutcomeAttempts()).isEqualTo(1);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        // Backoff: not due again immediately.
        assertThat(ConfidentialForcedOpVerifier.isDue(tx, Instant.now())).isFalse();

        tx.setExecutionOutcomeAttempts(ConfidentialForcedOpVerifier.MAX_ATTEMPTS - 1);
        tx.setExecutionOutcomeCheckedAt(Instant.now().minusSeconds(365L * 24 * 3600));
        verifier.verifyPending();

        assertThat(tx.getExecutionOutcome()).isEqualTo(Outcome.UNVERIFIED_DECRYPT_FAILED.name());
        assertThat(publishedEvent().decryptedAmount()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BlockchainTransaction tx(String method, Map<String, Object> params) {
        BlockchainTransaction tx = new BlockchainTransaction();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        tx.setTxHash(TX_HASH);
        tx.setStatus(BlockchainTransaction.Status.SUCCESS);
        tx.setMethodName(method);
        tx.setContractAddress(TOKEN);
        tx.setChainConfigId(chainConfigId);
        tx.setAssetId(UUID.randomUUID());
        tx.setDeploymentId(UUID.randomUUID());
        tx.setParams(params);
        when(txRepository.findByMethodNameInAndStatusAndExecutionOutcomeIsNull(
                ConfidentialForcedOpVerifier.METHODS, BlockchainTransaction.Status.SUCCESS))
                .thenAnswer(inv -> tx.getExecutionOutcome() == null ? List.of(tx) : List.of());
        when(txRepository.findById(tx.getId())).thenReturn(Optional.of(tx));
        return tx;
    }

    private static String topicAddress(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(address);
    }

    private static Log transferLog(String from, String to, BigInteger handle) {
        Log l = new Log();
        l.setAddress(TOKEN);
        l.setTopics(List.of(ConfidentialForcedOpVerifier.TRANSFER_TOPIC, topicAddress(from), topicAddress(to)));
        l.setData(Numeric.toHexStringWithPrefixZeroPadded(handle, 64));
        return l;
    }

    private static Log burnLog(String from, BigInteger handle) {
        Log l = new Log();
        l.setAddress(TOKEN);
        l.setTopics(List.of(ConfidentialForcedOpVerifier.BURN_TOPIC, topicAddress(from)));
        l.setData(Numeric.toHexStringWithPrefixZeroPadded(handle, 64));
        return l;
    }

    @SuppressWarnings("unchecked")
    private void stubReceipt(Log... logs) throws Exception {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(TX_HASH);
        receipt.setTo(TOKEN);
        receipt.setLogs(List.of(logs));
        EthGetTransactionReceipt response = new EthGetTransactionReceipt();
        response.setResult(receipt);
        Request<?, EthGetTransactionReceipt> request = mock(Request.class);
        doReturn(response).when(request).send();
        Web3j web3j = mock(Web3j.class);
        doReturn(request).when(web3j).ethGetTransactionReceipt(TX_HASH);
        when(evmContractService.evmClient(chainConfigId)).thenReturn(web3j);
    }

    private ConfidentialForcedOpOutcomeEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return (ConfidentialForcedOpOutcomeEvent) captor.getAllValues().getLast();
    }
}
