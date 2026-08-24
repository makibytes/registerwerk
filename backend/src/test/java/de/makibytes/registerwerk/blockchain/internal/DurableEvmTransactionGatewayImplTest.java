package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.abi.datatypes.Function;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableEvmTransactionGatewayImplTest {

    @Mock private DurableEvmSubmissionPort submissions;

    private DurableEvmTransactionGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        gateway = new DurableEvmTransactionGatewayImpl(submissions);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void persistsSignedPayloadBeforeDeferringBroadcastUntilCommit() {
        UUID chainId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        Function function = new Function("pause", List.of(), List.of());
        Map<String, Object> params = Map.of("reason", "incident");
        var prepared = new DurableEvmSubmissionPort.PreparedSubmission(submissionId, "0xhash");
        when(submissions.prepare(chainId, "0xcontract", function, params)).thenReturn(prepared);
        TransactionSynchronizationManager.initSynchronization();

        String txHash = gateway.submit(chainId, "0xcontract", function, params);

        assertThat(txHash).isEqualTo("0xhash");
        verify(submissions).prepare(chainId, "0xcontract", function, params);
        verify(submissions, never()).dispatch(submissionId);

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        InOrder order = inOrder(submissions);
        order.verify(submissions).prepare(chainId, "0xcontract", function, params);
        order.verify(submissions).dispatch(submissionId);
    }

    @Test
    void afterCommitDispatchFailureDoesNotTurnCommittedIntentIntoCallerFailure() {
        UUID submissionId = UUID.randomUUID();
        var prepared = new DurableEvmSubmissionPort.PreparedSubmission(submissionId, "0xhash");
        when(submissions.prepare(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(prepared);
        org.mockito.Mockito.doThrow(new IllegalStateException("RPC unavailable"))
                .when(submissions).dispatch(submissionId);

        assertThatCode(() -> gateway.submit(UUID.randomUUID(), "0xcontract",
                new Function("pause", List.of(), List.of()), Map.of()))
                .doesNotThrowAnyException();

        verify(submissions).dispatch(submissionId);
    }
}
