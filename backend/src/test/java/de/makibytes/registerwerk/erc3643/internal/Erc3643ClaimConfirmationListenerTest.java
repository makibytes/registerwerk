package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
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
@DisplayName("Erc3643ClaimConfirmationListener — closes the optimistic-write reorg gap for claim issuance/revocation")
class Erc3643ClaimConfirmationListenerTest {

    @Mock private OnchainClaimRepository claimRepository;
    @Mock private BlockchainTransactionService blockchainTransactionService;
    @Mock private ChainEffectRecorder chainEffectRecorder;
    @Mock private de.makibytes.registerwerk.shared.IsolatedTransactionExecutor isolatedTransactions;

    private Erc3643ClaimConfirmationListener listener;
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID claimId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new Erc3643ClaimConfirmationListener(
                claimRepository, blockchainTransactionService, chainEffectRecorder, isolatedTransactions);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, de.makibytes.registerwerk.shared.IsolatedTransactionExecutor.Work.class).run();
            return null;
        }).when(isolatedTransactions).run(any());
    }

    private OnchainClaim claimWithIssuanceTx() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setTxHash("0xissuetx");
        return claim;
    }

    @Test
    @DisplayName("a still-pending issuance tx leaves the row unresolved")
    void pendingIssuance_leavesUnresolved() {
        OnchainClaim claim = claimWithIssuanceTx();
        when(claimRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(claim));
        when(claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xissuetx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xissuetx")).thenReturn(Optional.empty());

        listener.resolvePending();

        verify(claimRepository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed issuance tx marks the claim confirmed and journals ERC3643_CLAIM_CONFIRMED")
    void confirmedIssuance_marksConfirmedAndJournals() {
        OnchainClaim claim = claimWithIssuanceTx();
        when(claimRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(claim));
        when(claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xissuetx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xissuetx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 100L, "0xblock100")));

        listener.resolvePending();

        assertThat(claim.isConfirmed()).isTrue();
        assertThat(claim.getChainConfigId()).isEqualTo(chainConfigId);
        verify(claimRepository).save(claim);
        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ERC3643_CLAIM_CONFIRMED");
        assertThat(captor.getValue().entityId()).isEqualTo(claimId);
    }

    @Test
    @DisplayName("a failed issuance tx clears txHash (stops polling) without ever setting confirmed=true")
    void failedIssuance_clearsTxHashWithoutConfirming() {
        OnchainClaim claim = claimWithIssuanceTx();
        when(claimRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(claim));
        when(claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()).thenReturn(List.of());
        when(blockchainTransactionService.isConfirmedFailure("0xissuetx")).thenReturn(true);

        listener.resolvePending();

        assertThat(claim.isConfirmed()).isFalse();
        assertThat(claim.getTxHash()).isNull();
        verify(claimRepository).save(claim);
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed revocation tx records exact location and journals a compensable effect")
    void confirmedRevocation_recordsLocationAndJournals() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setRevocationTxHash("0xrevoketx");
        when(claimRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of());
        when(claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()).thenReturn(List.of(claim));
        when(blockchainTransactionService.isConfirmedFailure("0xrevoketx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xrevoketx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 200L, "0xblock200")));

        listener.resolvePending();

        assertThat(claim.getRevokedAt()).isNotNull();
        assertThat(claim.getRevocationChainConfigId()).isEqualTo(chainConfigId);
        assertThat(claim.getRevocationBlockNumber()).isEqualTo(200L);
        assertThat(claim.getRevocationBlockHash()).isEqualTo("0xblock200");
        verify(claimRepository).save(claim);
        ArgumentCaptor<ChainEffectDescriptor> captor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ERC3643_CLAIM_REVOKED");
        assertThat(captor.getValue().blockHash()).isEqualTo("0xblock200");
    }

    @Test
    @DisplayName("a failed revocation tx clears revocationTxHash so it can be resubmitted")
    void failedRevocation_clearsForResubmission() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setRevocationTxHash("0xfailedrevoke");
        when(claimRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of());
        when(claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()).thenReturn(List.of(claim));
        when(blockchainTransactionService.isConfirmedFailure("0xfailedrevoke")).thenReturn(true);

        listener.resolvePending();

        assertThat(claim.getRevocationTxHash()).isNull();
        assertThat(claim.getRevokedAt()).isNull();
        assertThat(claim.getRevocationChainConfigId()).isNull();
        assertThat(claim.getRevocationBlockNumber()).isNull();
        assertThat(claim.getRevocationBlockHash()).isNull();
        verify(claimRepository).save(claim);
    }
}
