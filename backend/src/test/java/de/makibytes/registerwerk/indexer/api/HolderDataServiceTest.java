package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HolderDataService transfer-aggregation unit tests")
class HolderDataServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HolderDataService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HolderDataService(deploymentRepository, tokenTransferRepository, assetHolderRepository, eventPublisher);
    }

    private void givenTransfers(TokenTransfer... transfers) {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        // The service must only ever ask for FINAL transfers — ORPHANED (reorged-out, kept for
        // audit rather than deleted) and PROVISIONAL (not yet past the confirmation depth) rows
        // must never reach balance aggregation. Wiring the mock to this exact overload (rather
        // than any()) is what makes aggregatesBalancesFromTransfers etc. fail loudly if the
        // service regresses to the unfiltered query.
        when(tokenTransferRepository.findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(
                        eq(deploymentId), eq(FinalityLevel.FINALIZED), any()))
                .thenReturn(new PageImpl<>(List.of(transfers)));
    }

    private TokenTransfer transfer(String from, String to, String amount, Instant at) {
        return transfer(from, to, amount, at, FinalityLevel.FINALIZED);
    }

    private TokenTransfer transfer(String from, String to, String amount, Instant at,
                                    FinalityLevel finalityStatus) {
        TokenTransfer t = new TokenTransfer();
        t.setFromAddress(from);
        t.setToAddress(to);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(at);
        t.setFinalityStatus(finalityStatus);
        return t;
    }

    @Test
    @DisplayName("mint + transfer chain nets out to balances of pre-registered holders")
    void aggregatesBalancesFromTransfers() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(
                transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "1000", t0),
                transfer("0xAAA1", "0xBBB2", "300", t0.plusSeconds(60)),
                transfer("0xBBB2", "0x0000000000000000000000000000000000000000", "100", t0.plusSeconds(120)));
        AssetHolder aaa = holder("0xAAA1", "0");
        AssetHolder bbb = holder("0xBBB2", "0");
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(aaa, bbb)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<AssetHolder> saved = ArgumentCaptor.forClass(AssetHolder.class);
        verify(assetHolderRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AssetHolder::getWalletAddress, h -> h.getNominalAmount().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("0xAAA1", "700"),
                        org.assertj.core.groups.Tuple.tuple("0xBBB2", "200"));
    }

    @Test
    @DisplayName("existing on-chain holder is updated to the indexed net balance; manual rows untouched")
    void updatesExistingHolderAndLeavesManualRowsAlone() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(
                transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500", t0));

        AssetHolder onchainHolder = new AssetHolder();
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1"); // different casing than the indexed event
        onchainHolder.setNominalAmount(new BigDecimal("100"));

        AssetHolder manualRow = new AssetHolder();
        manualRow.setAssetId(assetId);
        manualRow.setWalletAddress("0xMANUAL");
        manualRow.setNominalAmount(new BigDecimal("42"));

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder, manualRow)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(onchainHolder.getNominalAmount()).isEqualByComparingTo("500");
        verify(assetHolderRepository, never()).save(manualRow);
    }

    @Test
    @DisplayName("no indexed transfers, no existing holders → nothing is written")
    void noTransfersNoWrites() {
        givenTransfers();
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.syncHoldersFromBlockchain(assetId);

        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unmapped transfer wallet fails closed before writing an invalid holder")
    void unmappedWalletFailsClosed() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "1000", t0));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no registered holder identity")
                .hasMessageContaining("0xaaa1");
        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("an existing holder whose balance changed publishes HolderBalanceSyncedEvent(newlyCreated=false)")
    void updatedHolderPublishesUpdatedEvent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500", t0));

        AssetHolder onchainHolder = new AssetHolder();
        UUID holderId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(onchainHolder, "id", holderId);
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1");
        onchainHolder.setNominalAmount(new BigDecimal("100"));

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<HolderBalanceSyncedEvent> captor = ArgumentCaptor.forClass(HolderBalanceSyncedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderId()).isEqualTo(holderId);
        assertThat(captor.getValue().newlyCreated()).isFalse();
    }

    @Test
    @DisplayName("ORPHANED and PROVISIONAL transfers never move the register's balance")
    void orphanedAndProvisionalTransfersAreExcludedFromBalance() {
        // Regression test for a bug where syncHoldersFromBlockchain summed every indexed row
        // regardless of finality_status: a reorged-out (ORPHANED) or not-yet-confirmed
        // (PROVISIONAL) transfer could move asset_holder.nominal_amount — the register itself.
        // Simulated here at the repository boundary: the FINAL-only query the service now uses
        // returns just the confirmed leg, standing in for ORPHANED/PROVISIONAL rows a real
        // database would have filtered out already.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(
                transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "500", t0,
                        FinalityLevel.FINALIZED));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(holder("0xAAA1", "0"))));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<AssetHolder> saved = ArgumentCaptor.forClass(AssetHolder.class);
        verify(assetHolderRepository).save(saved.capture());
        assertThat(saved.getValue().getNominalAmount()).isEqualByComparingTo("500");
        // The old, unfiltered query must never be called — that overload doesn't distinguish
        // FINAL from ORPHANED/PROVISIONAL and is exactly what caused the bug.
        verify(tokenTransferRepository, never())
                .findByDeploymentIdOrderByOccurredAtDesc(any(), any());
    }

    private AssetHolder holder(String wallet, String amount) {
        AssetHolder holder = new AssetHolder();
        org.springframework.test.util.ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
        holder.setAssetId(assetId);
        holder.setInvestorId(UUID.randomUUID());
        holder.setWalletAddress(wallet);
        holder.setNominalAmount(new BigDecimal(amount));
        return holder;
    }

    @Test
    @DisplayName("no-op sync (balance unchanged, already chain-derived) writes nothing and publishes no event")
    void noBalanceChangeDoesNotPublishEvent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "100", t0));

        AssetHolder onchainHolder = new AssetHolder();
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1");
        onchainHolder.setNominalAmount(new BigDecimal("100"));
        onchainHolder.setChainDerived(true);

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder)));

        service.syncHoldersFromBlockchain(assetId);

        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("first contact with a pre-existing manual row marks it chain-derived even without a balance change")
    void firstSyncMarksChainDerivedEvenIfBalanceCoincidentallyMatches() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "100", t0));

        AssetHolder preExisting = new AssetHolder();
        preExisting.setAssetId(assetId);
        preExisting.setWalletAddress("0xAAA1");
        preExisting.setNominalAmount(new BigDecimal("100"));
        // chainDerived defaults to false — this row has never been touched by chain sync before.

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(preExisting)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(preExisting.isChainDerived()).isTrue();
        // No event: the balance itself did not change, only the chain-derived bookkeeping.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a chain-derived wallet whose transfers all vanished (e.g. reorged out) is zeroed, not left stale")
    void vanishedWalletBalanceIsZeroed() {
        // The bug this fixes: a wallet's entire transfer set drops out of the FINALIZED window
        // (every one of its transfers orphaned by a reorg), so it no longer appears in `balances`
        // at all. Before the fix, syncHoldersFromBlockchain iterated only the wallets present in
        // the counted set, so this holder's stale non-zero balance was never corrected.
        givenTransfers(); // nothing left to count for this asset

        AssetHolder vanished = new AssetHolder();
        UUID holderId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(vanished, "id", holderId);
        vanished.setAssetId(assetId);
        vanished.setWalletAddress("0xVANISHED");
        vanished.setNominalAmount(new BigDecimal("500"));
        vanished.setChainDerived(true);

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(vanished)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(vanished.getNominalAmount()).isEqualByComparingTo("0");
        ArgumentCaptor<HolderBalanceSyncedEvent> captor = ArgumentCaptor.forClass(HolderBalanceSyncedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderId()).isEqualTo(holderId);
        assertThat(captor.getValue().newlyCreated()).isFalse();
    }

    @Test
    @DisplayName("an off-chain (never chain-derived) holder absent from the counted set is left alone")
    void nonChainDerivedHolderIsNeverZeroed() {
        givenTransfers(); // nothing to count

        AssetHolder manualRow = new AssetHolder();
        manualRow.setAssetId(assetId);
        manualRow.setWalletAddress("0xMANUAL");
        manualRow.setNominalAmount(new BigDecimal("42"));
        // chainDerived left at its default (false) — an off-chain register entry.

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(manualRow)));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(manualRow.getNominalAmount()).isEqualByComparingTo("42");
        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
