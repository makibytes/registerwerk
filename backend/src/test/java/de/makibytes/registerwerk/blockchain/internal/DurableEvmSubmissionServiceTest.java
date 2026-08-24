package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.abi.datatypes.Function;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableEvmSubmissionServiceTest {

    @Mock private EvmSignedSubmissionRepository repository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainTransactionService txService;
    @Mock private EvmSigner signer;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Web3j web3j;

    private DurableEvmSubmissionService service;
    private final UUID chainId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final String txHash = "0x" + "ab".repeat(32);

    @BeforeEach
    void setUp() {
        service = new DurableEvmSubmissionService(
                repository, chainConfigRepository, clientRegistry, evmContractService, txService);
    }

    @Test
    void preparePersistsDeterministicSignedBytesWithoutBroadcasting() {
        ChainConfig chain = chain();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(evmContractService.signer(chainId)).thenReturn(signer);
        when(evmContractService.prepareDurable(eq(chainId), eq(web3j), eq(signer), any(), any()))
                .thenReturn(new EvmContractService.PreparedRawTransaction(
                        txHash, "0x010203", 11155111L,
                        "0x" + "cd".repeat(20), BigInteger.valueOf(7)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            EvmSignedSubmission row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", submissionId);
            return row;
        });

        var prepared = service.prepare(chainId, "0x" + "ef".repeat(20),
                new Function("pause", List.of(), List.of()), Map.of("reason", "incident"));

        assertThat(prepared.id()).isEqualTo(submissionId);
        assertThat(prepared.txHash()).isEqualTo(txHash);
        verify(repository).saveAndFlush(any(EvmSignedSubmission.class));
        verify(evmContractService, never()).broadcastPrepared(any(), any(), any(), any());
        verify(txService, never()).recordPrepared(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ambiguousBroadcastThatMadeExpectedHashVisibleCompletesWithoutCreatingAnotherTransaction() throws Exception {
        EvmSignedSubmission row = preparedRow();
        when(repository.findByIdForUpdate(submissionId)).thenReturn(Optional.of(row));
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt())
                .thenReturn(Optional.empty(), Optional.of(new TransactionReceipt()));
        when(web3j.ethGetTransactionByHash(txHash).send().getTransaction()).thenReturn(Optional.empty());
        when(evmContractService.broadcastPrepared(chainId, web3j, "0x010203", txHash))
                .thenThrow(new RuntimeException("connection reset after provider accepted bytes"));

        service.dispatch(submissionId);

        assertThat(row.getStatus()).isEqualTo(EvmSignedSubmission.Status.BROADCAST);
        assertThat(row.getBroadcastAt()).isNotNull();
        assertThat(row.getAttemptCount()).isOne();
        verify(evmContractService).broadcastPrepared(chainId, web3j, "0x010203", txHash);
        verify(txService).recordPrepared(eq(txHash), eq("pause"), eq(chainId),
                eq("ETHEREUM"), eq("TESTNET"), any(), any(), any(), any());
    }

    @Test
    void unobservableAmbiguousFailureRetainsTheExactPreparedPayloadForRetry() throws Exception {
        EvmSignedSubmission row = preparedRow();
        when(repository.findByIdForUpdate(submissionId)).thenReturn(Optional.of(row));
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt())
                .thenReturn(Optional.empty());
        when(web3j.ethGetTransactionByHash(txHash).send().getTransaction()).thenReturn(Optional.empty());
        when(evmContractService.broadcastPrepared(chainId, web3j, "0x010203", txHash))
                .thenThrow(new RuntimeException("provider unavailable"));

        service.dispatch(submissionId);

        assertThat(row.getStatus()).isEqualTo(EvmSignedSubmission.Status.PREPARED);
        assertThat(row.getLastError()).contains("provider unavailable");
        assertThat(row.getAttemptCount()).isOne();
        verify(txService, never()).recordPrepared(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private ChainConfig chain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ETHEREUM_TESTNET");
        chain.setNetworkType(ChainConfig.NetworkType.TESTNET);
        return chain;
    }

    private EvmSignedSubmission preparedRow() {
        EvmSignedSubmission row = new EvmSignedSubmission();
        ReflectionTestUtils.setField(row, "id", submissionId);
        row.setChainConfigId(chainId);
        row.setChainId(BigInteger.valueOf(11155111L));
        row.setSenderAddress("0x" + "cd".repeat(20));
        row.setNonce(BigInteger.valueOf(7));
        row.setTxHash(txHash);
        row.setSignedPayload("0x010203");
        row.setChainName("ETHEREUM");
        row.setNetwork("TESTNET");
        row.setContractAddress("0x" + "ef".repeat(20));
        row.setMethodName("pause");
        row.setParams(Map.of("reason", "incident"));
        row.setActorName("operator");
        row.setActorRole("REGISTRY_ADMIN");
        return row;
    }
}
