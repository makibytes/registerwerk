package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Erc3643IdentityRegistryConfirmationListener — closes the optimistic-write reorg gap")
class Erc3643IdentityRegistryConfirmationListenerTest {

    @Mock private Erc3643IdentityRegistryRepository repository;
    @Mock private BlockchainTransactionService blockchainTransactionService;
    @Mock private ChainEffectRecorder chainEffectRecorder;
    @Mock private de.makibytes.registerwerk.shared.IsolatedTransactionExecutor isolatedTransactions;

    private Erc3643IdentityRegistryConfirmationListener listener;
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new Erc3643IdentityRegistryConfirmationListener(
                repository, blockchainTransactionService, chainEffectRecorder, isolatedTransactions);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, de.makibytes.registerwerk.shared.IsolatedTransactionExecutor.Work.class).run();
            return null;
        }).when(isolatedTransactions).run(any());
    }

    private Erc3643IdentityRegistry entryWithRegisteredTx() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", entryId);
        entry.setRegisteredByTx("0xregistertx");
        return entry;
    }

    @Test
    @DisplayName("a still-pending registration tx (not yet confirmed, not failed) leaves the row unresolved")
    void pendingRegistration_leavesUnresolved() {
        Erc3643IdentityRegistry entry = entryWithRegisteredTx();
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of(entry));
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xregistertx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xregistertx")).thenReturn(Optional.empty());

        listener.resolvePending();

        verify(repository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed registration tx marks the row confirmed and journals ERC3643_IDENTITY_REGISTERED")
    void confirmedRegistration_marksConfirmedAndJournals() {
        Erc3643IdentityRegistry entry = entryWithRegisteredTx();
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of(entry));
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xregistertx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xregistertx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 100L, "0xblock100")));

        listener.resolvePending();

        assertThat(entry.isRegistrationConfirmed()).isTrue();
        assertThat(entry.getRegistrationBlockNumber()).isEqualTo(100L);
        assertThat(entry.getRegistrationBlockHash()).isEqualTo("0xblock100");
        verify(repository).save(entry);
        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ERC3643_IDENTITY_REGISTERED");
        assertThat(captor.getValue().chainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().blockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().entityId()).isEqualTo(entryId);
    }

    @Test
    @DisplayName("a confirmed registration cannot reactivate an entry with later removal intent")
    void confirmedRegistration_preservesLaterRemovalIntent() {
        Erc3643IdentityRegistry entry = entryWithRegisteredTx();
        java.time.Instant removalIntentRecordedAt = java.time.Instant.parse("2026-08-23T10:15:30Z");
        entry.setRemovedAt(removalIntentRecordedAt);
        entry.setRemovedByTx("0xremove-pending");
        entry.setRemovalConfirmed(false);
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of(entry));
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xregistertx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xregistertx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(
                        chainConfigId, 100L, "0xblock100")));

        listener.resolvePending();

        assertThat(entry.getRemovedAt()).isEqualTo(removalIntentRecordedAt);
        assertThat(entry.isActive()).isFalse();
    }

    @Test
    @DisplayName("a failed registration tx soft-removes the optimistic mirror without journalling")
    void failedRegistration_softRemovesOptimisticMirror() {
        Erc3643IdentityRegistry entry = entryWithRegisteredTx();
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of(entry));
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xregistertx")).thenReturn(true);

        listener.resolvePending();

        assertThat(entry.isRegistrationConfirmed()).isTrue();
        assertThat(entry.isActive()).isFalse();
        verify(repository).save(entry);
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed removal tx marks the row confirmed and journals ERC3643_IDENTITY_REMOVED")
    void confirmedRemoval_marksConfirmedAndJournals() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", entryId);
        entry.setRemovedByTx("0xremovetx");
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of());
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of(entry));
        when(blockchainTransactionService.isConfirmedFailure("0xremovetx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xremovetx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 200L, "0xblock200")));

        listener.resolvePending();

        assertThat(entry.isRemovalConfirmed()).isTrue();
        assertThat(entry.isActive()).isFalse();
        assertThat(entry.getRemovalBlockNumber()).isEqualTo(200L);
        assertThat(entry.getRemovalBlockHash()).isEqualTo("0xblock200");
        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ERC3643_IDENTITY_REMOVED");
        assertThat(captor.getValue().blockNumber()).isEqualTo(200L);
    }

    @Test
    @DisplayName("a pending removal remains fail-closed and keeps its intent timestamp")
    void pendingRemoval_keepsFailClosedIntent() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", entryId);
        java.time.Instant removalIntentRecordedAt = java.time.Instant.parse("2026-08-23T10:15:30Z");
        entry.setRemovedAt(removalIntentRecordedAt);
        entry.setRemovedByTx("0xremovetx");
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of());
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of(entry));
        when(blockchainTransactionService.isConfirmedFailure("0xremovetx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xremovetx")).thenReturn(Optional.empty());

        listener.resolvePending();

        assertThat(entry.getRemovedAt()).isEqualTo(removalIntentRecordedAt);
        assertThat(entry.isActive()).isFalse();
        assertThat(entry.isRemovalConfirmed()).isFalse();
        verify(repository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a failed removal tx restores the optimistically removed mirror")
    void failedRemoval_restoresOptimisticMirror() {
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", entryId);
        entry.setRemovedAt(java.time.Instant.now());
        entry.setRemovedByTx("0xremovetx");
        entry.setRemovalBlockNumber(199L);
        entry.setRemovalBlockHash("0xorphaned-block");
        when(repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()).thenReturn(List.of());
        when(repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()).thenReturn(List.of(entry));
        when(blockchainTransactionService.isConfirmedFailure("0xremovetx")).thenReturn(true);

        listener.resolvePending();

        assertThat(entry.isRemovalConfirmed()).isTrue();
        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getRemovedByTx()).isEqualTo("0xremovetx");
        assertThat(entry.getRemovalBlockNumber()).isNull();
        assertThat(entry.getRemovalBlockHash()).isNull();
        verify(repository).save(entry);
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }
}
