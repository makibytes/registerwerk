package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.events.OutboxRecoveryEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRecoveryServiceTest {

    @Mock private EvmSignedSubmissionRepository repository;
    @Mock private BlockchainTransactionRepository transactions;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainTransactionService txService;
    @Mock private DurableEvmSubmissionPort submissions;
    @Mock private ApplicationEventPublisher events;
    @Mock private IsolatedTransactionExecutor isolated;
    @Mock private EvmSigner signer;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Web3j web3j;

    private OutboxRecoveryService service;
    private final UUID chainId = UUID.randomUUID();
    private final UUID rowId = UUID.randomUUID();
    private final String sender = "0x" + "cd".repeat(20);
    private final String hash = "0x" + "ab".repeat(32);
    private final String newHash = "0x" + "12".repeat(32);

    @BeforeEach
    void setUp() {
        service = new OutboxRecoveryService(repository, transactions, chainConfigRepository, clientRegistry,
                evmContractService, txService, submissions, events, new OutboxProperties(),
                new SimpleMeterRegistry(), isolated);
    }

    @Test
    void repriceSupersedesTheOriginalAndSavesAReplacementAtTheSameNonce() throws Exception {
        EvmSignedSubmission row = row("registerIdentity", EvmSignedSubmission.Status.PREPARED);
        stubReplaceable(row);
        when(evmContractService.resign(eq(chainId), eq(web3j), eq(signer), eq("0x010203"),
                eq(EvmContractService.ReplacementKind.REPRICE), anyInt()))
                .thenReturn(new EvmContractService.PreparedRawTransaction(
                        newHash, "0x0405", 11155111L, sender, BigInteger.valueOf(7)));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var actor = new OutboxRecoveryService.Actor(UUID.randomUUID(), "op", "REGISTRY_ADMIN", UUID.randomUUID(), null);
        service.reprice(chainId, rowId, actor, "fee bump requested by operator");

        assertThat(row.getStatus()).isEqualTo(EvmSignedSubmission.Status.SUPERSEDED);
        assertThat(row.getSupersededByTxHash()).isEqualTo(newHash);
        ArgumentCaptor<EvmSignedSubmission> saved = ArgumentCaptor.forClass(EvmSignedSubmission.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        EvmSignedSubmission replacement = saved.getAllValues().get(1);
        assertThat(replacement.getKind()).isEqualTo(EvmSignedSubmission.Kind.REPRICE);
        assertThat(replacement.getNonce()).isEqualTo(row.getNonce());
        assertThat(replacement.getReplacesTxHash()).isEqualTo(hash);
        assertThat(replacement.getStatus()).isEqualTo(EvmSignedSubmission.Status.PREPARED);
        assertThat(replacement.getMethodName()).isEqualTo("registerIdentity");
        // the business layer keeps the ORIGINAL hash; its record just learns about the replacement
        verify(txService).noteReplacement(hash, newHash);
        verify(txService, never()).recordPrepared(any(), any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<OutboxRecoveryEvent> event = ArgumentCaptor.forClass(OutboxRecoveryEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("EVM_OUTBOX_REPRICED");
        assertThat(event.getValue().dualControlApproverId()).isEqualTo(actor.approverId());
    }

    @Test
    void cancelOfARegulatoryOperationIsAllowedForTheOperatorAndGetsItsOwnTransactionRecord() throws Exception {
        EvmSignedSubmission row = row("forcedTransfer", EvmSignedSubmission.Status.BROADCAST);
        stubReplaceable(row);
        when(evmContractService.resign(eq(chainId), eq(web3j), eq(signer), any(),
                eq(EvmContractService.ReplacementKind.CANCEL), anyInt()))
                .thenReturn(new EvmContractService.PreparedRawTransaction(
                        newHash, "0x0405", 11155111L, sender, BigInteger.valueOf(7)));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.cancel(chainId, rowId, new OutboxRecoveryService.Actor(UUID.randomUUID(), "op", "REGISTRY_ADMIN",
                UUID.randomUUID(), null), "court order withdrawn");

        ArgumentCaptor<EvmSignedSubmission> saved = ArgumentCaptor.forClass(EvmSignedSubmission.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        EvmSignedSubmission cancel = saved.getAllValues().get(1);
        assertThat(cancel.getKind()).isEqualTo(EvmSignedSubmission.Kind.CANCEL);
        assertThat(cancel.getContractAddress()).isEqualTo(sender);
        assertThat(cancel.getParams()).containsEntry("cancelsTxHash", hash);
        verify(txService).recordPrepared(eq(newHash), eq("cancelNonce"), eq(chainId), any(), any(),
                eq(sender), any(), any(), any());
        verify(txService).noteReplacement(hash, newHash);
    }

    @Test
    void replacementIsRefusedWhenTheNonceIsAlreadyConsumedOnChain() throws Exception {
        EvmSignedSubmission row = row("registerIdentity", EvmSignedSubmission.Status.BROADCAST);
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt()).thenReturn(Optional.empty());
        when(evmContractService.transactionCount(web3j, sender, DefaultBlockParameterName.LATEST))
                .thenReturn(BigInteger.valueOf(8));

        assertThatThrownBy(() -> service.reprice(chainId, rowId,
                new OutboxRecoveryService.Actor(UUID.randomUUID(), "op", "REGISTRY_ADMIN", null, null), "reason text long"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already consumed");
        verify(evmContractService, never()).resign(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void replacementIsRefusedForRowsThatAreAlreadySupersededOrAbandoned() {
        EvmSignedSubmission row = row("registerIdentity", EvmSignedSubmission.Status.ABANDONED);
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.cancel(chainId, rowId,
                new OutboxRecoveryService.Actor(UUID.randomUUID(), "op", "REGISTRY_ADMIN", null, null), "reason text long"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void escalateNeverRepricesARegulatoryOperationAutomatically() {
        EvmSignedSubmission row = row("forcedTransfer", EvmSignedSubmission.Status.PREPARED);
        row.setLastErrorClass(EvmSignedSubmission.ErrorClass.UNDERPRICED);
        row.setAttemptCount(50);
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));

        service.escalate(rowId);

        verify(evmContractService, never()).resign(any(), any(), any(), any(), any(), anyInt());
        assertThat(row.getStatus()).isEqualTo(EvmSignedSubmission.Status.PREPARED);
    }

    @Test
    void escalateRepricesAnAllowListedCallOnlyAfterEnoughFeeFailures() throws Exception {
        EvmSignedSubmission row = row("registerIdentity", EvmSignedSubmission.Status.PREPARED);
        row.setLastErrorClass(EvmSignedSubmission.ErrorClass.BASE_FEE);
        row.setAttemptCount(2);
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));

        service.escalate(rowId);
        verify(evmContractService, never()).resign(any(), any(), any(), any(), any(), anyInt());

        row.setAttemptCount(4);
        stubReplaceable(row);
        when(evmContractService.resign(eq(chainId), eq(web3j), eq(signer), any(),
                eq(EvmContractService.ReplacementKind.REPRICE), anyInt()))
                .thenReturn(new EvmContractService.PreparedRawTransaction(
                        newHash, "0x0405", 11155111L, sender, BigInteger.valueOf(7)));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.escalate(rowId);

        assertThat(row.getStatus()).isEqualTo(EvmSignedSubmission.Status.SUPERSEDED);
    }

    @Test
    void escalateIgnoresNonFeeFailures() {
        EvmSignedSubmission row = row("registerIdentity", EvmSignedSubmission.Status.PREPARED);
        row.setLastErrorClass(EvmSignedSubmission.ErrorClass.INSUFFICIENT_FUNDS);
        row.setAttemptCount(50);
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));

        service.escalate(rowId);

        verify(evmContractService, never()).resign(any(), any(), any(), any(), any(), anyInt());
    }

    private void stubReplaceable(EvmSignedSubmission row) throws Exception {
        when(repository.findByIdForUpdate(rowId)).thenReturn(Optional.of(row));
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_TESTNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt()).thenReturn(Optional.empty());
        when(evmContractService.transactionCount(web3j, sender, DefaultBlockParameterName.LATEST))
                .thenReturn(BigInteger.valueOf(7));
        when(evmContractService.signer(chainId)).thenReturn(signer);
        when(signer.address()).thenReturn(sender);
    }

    @Test
    void refreshGaugesExposeOldestPreparedAgeAndStuckCountUnderTheNamesTheAlertsRead() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        OutboxRecoveryService svc = new OutboxRecoveryService(repository, transactions, chainConfigRepository,
                clientRegistry, evmContractService, txService, submissions, events, new OutboxProperties(),
                prometheus, isolated);
        EvmSignedSubmissionRepository.SignerBacklog backlog = org.mockito.Mockito.mock(
                EvmSignedSubmissionRepository.SignerBacklog.class);
        when(backlog.getChainId()).thenReturn(new java.math.BigDecimal("11155111"));
        when(backlog.getSenderAddress()).thenReturn(sender);
        when(backlog.getPreparedCount()).thenReturn(3L);
        when(backlog.getOldestCreatedAt()).thenReturn(java.time.Instant.now().minusSeconds(720));
        when(repository.preparedBacklogPerSigner()).thenReturn(java.util.List.of(backlog))
                .thenReturn(java.util.List.of());
        EvmSignedSubmission stuck = row("registerIdentity", EvmSignedSubmission.Status.PREPARED);
        when(repository.findAllStuck(any())).thenReturn(java.util.List.of(stuck, stuck))
                .thenReturn(java.util.List.of());

        svc.refreshGauges();

        // EvmOutboxPreparedStuck (> 600) and EvmOutboxBroadcastStuck (> 0) read exactly these series
        String scrape = prometheus.scrape();
        assertThat(scrape).contains("registerwerk_outbox_oldest_prepared_age_seconds{chain_id=\"11155111\",signer=\"" + sender + "\"}");
        assertThat(scrape).contains("registerwerk_outbox_stuck_count{chain_id=\"11155111\",signer=\"" + sender + "\"} 2.0");
        assertThat(prometheus.get("registerwerk.outbox.oldest_prepared_age_seconds").gauge().value())
                .isBetween(719.0, 730.0);
        assertThat(prometheus.get("registerwerk.outbox.prepared_count").gauge().value()).isEqualTo(3.0);

        // a signer that drained reads 0, not its last value (the alert must resolve)
        svc.refreshGauges();
        assertThat(prometheus.get("registerwerk.outbox.oldest_prepared_age_seconds").gauge().value()).isZero();
        assertThat(prometheus.get("registerwerk.outbox.prepared_count").gauge().value()).isZero();
        assertThat(prometheus.get("registerwerk.outbox.stuck_count").gauge().value()).isZero();
    }

    private ChainConfig chain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ETHEREUM_TESTNET");
        return chain;
    }

    private EvmSignedSubmission row(String method, EvmSignedSubmission.Status status) {
        EvmSignedSubmission row = new EvmSignedSubmission();
        ReflectionTestUtils.setField(row, "id", rowId);
        row.setChainConfigId(chainId);
        row.setChainId(BigInteger.valueOf(11155111L));
        row.setSenderAddress(sender);
        row.setNonce(BigInteger.valueOf(7));
        row.setTxHash(hash);
        row.setSignedPayload("0x010203");
        row.setStatus(status);
        if (status == EvmSignedSubmission.Status.BROADCAST) row.setBroadcastAt(java.time.Instant.now());
        row.setChainName("ETHEREUM");
        row.setNetwork("TESTNET");
        row.setContractAddress("0x" + "ef".repeat(20));
        row.setMethodName(method);
        row.setParams(Map.of("k", "v"));
        row.setActorName("operator");
        row.setActorRole("REGISTRY_ADMIN");
        return row;
    }
}
