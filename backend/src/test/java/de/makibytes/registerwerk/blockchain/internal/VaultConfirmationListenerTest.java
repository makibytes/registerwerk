package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VaultConfirmationListener — closes the optimistic-write reorg gap for vault admin ops")
class VaultConfirmationListenerTest {

    @Mock private VaultNavStrikeRepository navStrikeRepository;
    @Mock private VaultRequestRepository vaultRequestRepository;
    @Mock private AssetVaultStateRepository vaultStateRepository;
    @Mock private BlockchainTransactionService blockchainTransactionService;
    @Mock private ChainEffectRecorder chainEffectRecorder;
    @Mock private de.makibytes.registerwerk.shared.IsolatedTransactionExecutor isolatedTransactions;

    private VaultConfirmationListener listener;
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new VaultConfirmationListener(
                navStrikeRepository, vaultRequestRepository, vaultStateRepository,
                blockchainTransactionService, chainEffectRecorder, isolatedTransactions);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, de.makibytes.registerwerk.shared.IsolatedTransactionExecutor.Work.class).run();
            return null;
        }).when(isolatedTransactions).run(any());
        when(vaultRequestRepository.findByFulfilledTxIsNotNullAndConfirmedFalse()).thenReturn(List.of());
        when(vaultRequestRepository.findByCancelledTxIsNotNullAndConfirmedFalse()).thenReturn(List.of());
        when(vaultStateRepository.findByDepositCapTxHashIsNotNull()).thenReturn(List.of());
        when(navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of());
    }

    private VaultNavStrike strike(UUID id, long strikeId, String txHash) {
        VaultNavStrike strike = new VaultNavStrike();
        ReflectionTestUtils.setField(strike, "id", id);
        strike.setAssetId(assetId);
        strike.setStrikeId(strikeId);
        strike.setNavPerShare(new BigDecimal("1.0").add(BigDecimal.valueOf(strikeId, 2)));
        strike.setEffectiveAt(Instant.ofEpochSecond(1_000_000 + strikeId));
        strike.setTxHash(txHash);
        return strike;
    }

    @Test
    @DisplayName("a confirmed NAV strike applies to AssetVaultState and journals VAULT_NAV_STRIKE_CONFIRMED")
    void confirmedNavStrike_appliesAndJournals() {
        VaultNavStrike strike = strike(UUID.randomUUID(), 1L, "0xstriketx");
        when(navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(strike));
        when(blockchainTransactionService.isConfirmedFailure("0xstriketx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xstriketx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 100L, "0xblock100")));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId)).thenReturn(List.of(strike));
        when(vaultStateRepository.findByAssetIdForUpdate(assetId)).thenReturn(Optional.empty());

        listener.resolvePending();

        assertThat(strike.isConfirmed()).isTrue();
        assertThat(strike.getBlockHash()).isEqualTo("0xblock100");
        verify(navStrikeRepository).save(strike);
        ArgumentCaptor<AssetVaultState> stateCaptor = ArgumentCaptor.forClass(AssetVaultState.class);
        verify(vaultStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLatestNavPerShare()).isEqualByComparingTo(strike.getNavPerShare());
        assertThat(stateCaptor.getValue().getLatestNavStrikeAt()).isEqualTo(strike.getEffectiveAt());
        assertThat(stateCaptor.getValue().getLatestNavStrikeId()).isEqualTo(strike.getId());

        ArgumentCaptor<ChainEffectDescriptor> effectCaptor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effectCaptor.capture());
        assertThat(effectCaptor.getValue().effectType()).isEqualTo("VAULT_NAV_STRIKE_CONFIRMED");
        assertThat(effectCaptor.getValue().entityId()).isEqualTo(strike.getId());
    }

    @Test
    @DisplayName("a confirmed but superseded NAV strike is marked confirmed but not applied to AssetVaultState")
    void supersededNavStrike_notApplied() {
        VaultNavStrike olderStrike = strike(UUID.randomUUID(), 1L, "0xolder");
        VaultNavStrike newerConfirmed = strike(UUID.randomUUID(), 2L, "0xnewer");
        newerConfirmed.setConfirmed(true);
        newerConfirmed.setChainConfigId(chainConfigId);
        newerConfirmed.setBlockNumber(101L);
        newerConfirmed.setBlockHash("0xblock101");
        when(navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(olderStrike));
        when(blockchainTransactionService.isConfirmedFailure("0xolder")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xolder"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 100L, "0xblock100")));
        when(navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId))
                .thenReturn(List.of(newerConfirmed, olderStrike));

        listener.resolvePending();

        assertThat(olderStrike.isConfirmed()).isTrue();
        verify(navStrikeRepository).save(olderStrike);
        verify(vaultStateRepository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a failed NAV strike tx is marked confirmed (stops polling) without touching AssetVaultState")
    void failedNavStrike_marksConfirmedWithoutApplying() {
        VaultNavStrike strike = strike(UUID.randomUUID(), 1L, "0xfailed");
        when(navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()).thenReturn(List.of(strike));
        when(blockchainTransactionService.isConfirmedFailure("0xfailed")).thenReturn(true);

        listener.resolvePending();

        assertThat(strike.isConfirmed()).isTrue();
        verify(navStrikeRepository).save(strike);
        verify(vaultStateRepository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    private VaultRequest request(UUID id) {
        VaultRequest request = new VaultRequest();
        ReflectionTestUtils.setField(request, "id", id);
        request.setAssetId(assetId);
        request.setRequestId(BigInteger.TEN);
        request.setRequestType(VaultRequestType.REDEEM);
        request.setRequestStatus(VaultRequestStatus.PENDING);
        return request;
    }

    @Test
    @DisplayName("a confirmed fulfilment tx flips the request to FULFILLED and journals VAULT_REQUEST_RESOLVED")
    void confirmedFulfillment_flipsAndJournals() {
        VaultRequest request = request(UUID.randomUUID());
        request.setFulfilledTx("0xfulfiltx");
        when(vaultRequestRepository.findByFulfilledTxIsNotNullAndConfirmedFalse()).thenReturn(List.of(request));
        when(blockchainTransactionService.isConfirmedFailure("0xfulfiltx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xfulfiltx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 300L, "0xblock300")));

        listener.resolvePending();

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.FULFILLED);
        assertThat(request.getFulfilledAt()).isNotNull();
        assertThat(request.isConfirmed()).isTrue();
        assertThat(request.getBlockHash()).isEqualTo("0xblock300");
        verify(vaultRequestRepository).save(request);
        ArgumentCaptor<ChainEffectDescriptor> effectCaptor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effectCaptor.capture());
        assertThat(effectCaptor.getValue().effectType()).isEqualTo("VAULT_REQUEST_RESOLVED");
    }

    @Test
    @DisplayName("a failed fulfilment tx clears fulfilledTx so the request can be resubmitted")
    void failedFulfillment_clearsForResubmission() {
        VaultRequest request = request(UUID.randomUUID());
        request.setFulfilledTx("0xfailedfulfil");
        request.setNavAtFulfill(new BigDecimal("1.5"));
        when(vaultRequestRepository.findByFulfilledTxIsNotNullAndConfirmedFalse()).thenReturn(List.of(request));
        when(blockchainTransactionService.isConfirmedFailure("0xfailedfulfil")).thenReturn(true);

        listener.resolvePending();

        assertThat(request.getFulfilledTx()).isNull();
        assertThat(request.getNavAtFulfill()).isNull();
        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        verify(vaultRequestRepository).save(request);
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed cancellation tx flips the request to CANCELLED and journals VAULT_REQUEST_RESOLVED")
    void confirmedCancellation_flipsAndJournals() {
        VaultRequest request = request(UUID.randomUUID());
        request.setCancelledTx("0xcanceltx");
        when(vaultRequestRepository.findByCancelledTxIsNotNullAndConfirmedFalse()).thenReturn(List.of(request));
        when(blockchainTransactionService.isConfirmedFailure("0xcanceltx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xcanceltx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 400L, "0xblock400")));

        listener.resolvePending();

        assertThat(request.getRequestStatus()).isEqualTo(VaultRequestStatus.CANCELLED);
        assertThat(request.isConfirmed()).isTrue();
        assertThat(request.getBlockHash()).isEqualTo("0xblock400");
        verify(vaultRequestRepository).save(request);
        verify(chainEffectRecorder).recordFinalized(any());
    }

    @Test
    @DisplayName("a confirmed deposit-cap tx applies pendingDepositCap and journals its pre-image")
    void confirmedDepositCap_appliesAndJournals() {
        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setDepositCap(BigInteger.valueOf(100_000L));
        UUID previousChainConfigId = UUID.randomUUID();
        state.setDepositCapChainConfigId(previousChainConfigId);
        state.setDepositCapBlockNumber(400L);
        state.setDepositCapBlockHash("0xblock400");
        state.setDepositCapConfirmedTxHash("0xprevious-cap-tx");
        state.setPendingDepositCap(BigInteger.valueOf(500_000L));
        state.setDepositCapTxHash("0xcaptx");
        when(vaultStateRepository.findByDepositCapTxHashIsNotNull()).thenReturn(List.of(state));
        when(blockchainTransactionService.isConfirmedFailure("0xcaptx")).thenReturn(false);
        when(blockchainTransactionService.confirmedLocation("0xcaptx"))
                .thenReturn(Optional.of(new BlockchainTransactionService.ConfirmedTxLocation(chainConfigId, 500L, "0xblock500")));

        listener.resolvePending();

        assertThat(state.getDepositCap()).isEqualByComparingTo(BigInteger.valueOf(500_000L));
        assertThat(state.getPendingDepositCap()).isNull();
        assertThat(state.getDepositCapTxHash()).isNull();
        assertThat(state.getDepositCapBlockHash()).isEqualTo("0xblock500");
        assertThat(state.getDepositCapConfirmedTxHash()).isEqualTo("0xcaptx");
        verify(vaultStateRepository).save(state);
        ArgumentCaptor<ChainEffectDescriptor> effectCaptor = ArgumentCaptor.forClass(ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effectCaptor.capture());
        assertThat(effectCaptor.getValue().effectType()).isEqualTo("VAULT_DEPOSIT_CAP_CONFIRMED");
        assertThat(effectCaptor.getValue().beforeState()).containsEntry("depositCap", "100000");
        assertThat(effectCaptor.getValue().beforeState())
                .containsEntry("chainConfigId", previousChainConfigId.toString())
                .containsEntry("blockNumber", 400L)
                .containsEntry("blockHash", "0xblock400")
                .containsEntry("txHash", "0xprevious-cap-tx");
        assertThat(effectCaptor.getValue().afterState()).containsEntry("depositCap", "500000");
    }

    @Test
    @DisplayName("a failed deposit-cap tx discards the pending value without touching depositCap")
    void failedDepositCap_discardsPending() {
        AssetVaultState state = new AssetVaultState();
        state.setAssetId(assetId);
        state.setPendingDepositCap(BigInteger.valueOf(999L));
        state.setDepositCapTxHash("0xfailedcap");
        when(vaultStateRepository.findByDepositCapTxHashIsNotNull()).thenReturn(List.of(state));
        when(blockchainTransactionService.isConfirmedFailure("0xfailedcap")).thenReturn(true);

        listener.resolvePending();

        assertThat(state.getDepositCap()).isNull();
        assertThat(state.getPendingDepositCap()).isNull();
        assertThat(state.getDepositCapTxHash()).isNull();
        verify(vaultStateRepository).save(state);
    }
}
