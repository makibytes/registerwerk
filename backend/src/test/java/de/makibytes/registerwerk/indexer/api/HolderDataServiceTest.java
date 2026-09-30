package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderSyncStatusPort;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import de.makibytes.registerwerk.indexer.events.HolderSyncBlockedEvent;
import de.makibytes.registerwerk.indexer.events.HolderSyncRestoredEvent;
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
    @Mock private HolderSyncStatusPort holderSyncStatusPort;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetLookupPort assetLookupPort;
    @Mock private de.makibytes.registerwerk.chain.api.ChainConfigRepository chainConfigRepository;
    @Mock private de.makibytes.registerwerk.indexer.internal.GraphNodeClient graphNodeClient;
    @Mock private IndexingCoverage indexingCoverage;

    private HolderDataService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HolderDataService(deploymentRepository, tokenTransferRepository, assetHolderRepository, eventPublisher,
                holderSyncStatusPort, assetLookupPort, chainConfigRepository, graphNodeClient, indexingCoverage);
        org.mockito.Mockito.lenient().when(indexingCoverage.evaluate(any())).thenReturn(IndexingCoverage.Coverage.ok());
    }

    private void givenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard standard) {
        when(assetLookupPort.findById(assetId)).thenReturn(java.util.Optional.of(
                new de.makibytes.registerwerk.deployment.api.AssetLookupPort.AssetInfo(
                        assetId, "A", null, standard, null, null, null, null, "ISSUED")));
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
    @DisplayName("T2-18: an unmapped wallet persists BLOCKED with the wallets and audits the transition")
    void unmappedWalletPersistsBlockedState() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xPOOL1", "1000", t0));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), eq(List.of("0xpool1")), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class);

        verify(holderSyncStatusPort).markBlocked(eq(assetId), any(), eq(List.of("0xpool1")), any());
        verify(holderSyncStatusPort, never()).markReconciled(any(), any());
        verify(eventPublisher).publishEvent(new HolderSyncBlockedEvent(assetId, List.of("0xpool1")));
    }

    @Test
    @DisplayName("T2-18: a still-blocked re-run (no state change) does not re-audit")
    void unchangedBlockedStateIsNotReAudited() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xPOOL1", "1000", t0));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(holderSyncStatusPort.markBlocked(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("T2-18: a completed sync marks the register reconciled; leaving BLOCKED is audited")
    void completedSyncMarksReconciled() {
        givenTransfers();
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(holderSyncStatusPort.markReconciled(eq(assetId), any())).thenReturn(true);

        service.syncHoldersFromBlockchain(assetId);

        verify(holderSyncStatusPort).markReconciled(eq(assetId), any());
        verify(eventPublisher).publishEvent(new HolderSyncRestoredEvent(assetId));
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

    private TokenTransfer transferNoAmount(String from, String to) {
        TokenTransfer t = transfer(from, to, "1", Instant.parse("2026-01-01T00:00:00Z"));
        t.setAmount(null);
        return t;
    }

    private AssetHolder removedHolder(String wallet) {
        AssetHolder h = holder(wallet, "0");
        h.setRemovedAt(Instant.parse("2026-02-01T00:00:00Z"));
        return h;
    }

    @Test
    @DisplayName("T3-17: a balance on a wallet whose only entry is removed BLOCKS the sync and never writes the closed row")
    void removedRowWithBalanceBlocksSync() {
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500",
                Instant.parse("2026-01-01T00:00:00Z")));
        AssetHolder removed = removedHolder("0xaaa1");
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(removed)));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), eq(List.of("0xaaa1")), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("closed register entry");
        verify(assetHolderRepository, never()).save(any());
        assertThat(removed.getNominalAmount()).isEqualByComparingTo("0");
        verify(holderSyncStatusPort, never()).markReconciled(any(), any());
    }

    @Test
    @DisplayName("T3-17: with an active row and a removed history row for the same wallet, only the active row is updated")
    void reentryAfterRemovalUpdatesOnlyTheActiveRow() {
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "500",
                Instant.parse("2026-01-01T00:00:00Z")));
        AssetHolder removed = removedHolder("0xaaa1");
        AssetHolder active = holder("0xaaa1", "0");
        // removed row listed last: a single wallet-keyed map would let it shadow the active row
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(active, removed)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(active.getNominalAmount()).isEqualByComparingTo("500");
        assertThat(active.isChainDerived()).isTrue();
        assertThat(removed.getNominalAmount()).isEqualByComparingTo("0");
        verify(assetHolderRepository, never()).save(removed);
    }

    @Test
    @DisplayName("T3-09: active non-chain-derived rows with a positive nominal are counted as off-chain rows, not BLOCKED")
    void syncReportsOffchainRowsOnDeployedAsset() {
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500",
                Instant.parse("2026-01-01T00:00:00Z")));
        AssetHolder chain = holder("0xaaa1", "500");
        chain.setChainDerived(true);
        AssetHolder manual = holder("0xbbb2", "40");
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(chain, manual)));

        service.syncHoldersFromBlockchain(assetId);

        verify(holderSyncStatusPort).recordOffchainRows(assetId, 1);
        verify(holderSyncStatusPort, never()).markBlocked(any(), any(), any(), any());
    }

    @Test
    @DisplayName("T3-20: a fungible transfer without amount BLOCKS instead of counting as 1")
    void nullAmountOnFungibleBlocks() {
        givenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard.ERC20);
        givenTransfers(transferNoAmount("0x0000000000000000000000000000000000000000", "0xaaa1"));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("without amount");
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @DisplayName("T3-20: an ERC-721 transfer without amount counts as one unit")
    void erc721NullAmountCountsOne() {
        givenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard.ERC721);
        givenTransfers(transferNoAmount("0x0000000000000000000000000000000000000000", "0xaaa1"));
        AssetHolder h = holder("0xaaa1", "0");
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(h.getNominalAmount()).isEqualByComparingTo("1");
    }

    private void givenErc3525Deployment(de.makibytes.registerwerk.chain.api.ChainConfig chain) {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChainConfigId(UUID.randomUUID());
        deployment.setContractAddress("0xToken");
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        when(chainConfigRepository.findById(deployment.getChainConfigId())).thenReturn(java.util.Optional.of(chain));
        givenStandard(de.makibytes.registerwerk.deployment.api.TokenStandard.ERC3525);
    }

    private de.makibytes.registerwerk.chain.api.ChainConfig graphChain() {
        de.makibytes.registerwerk.chain.api.ChainConfig chain = new de.makibytes.registerwerk.chain.api.ChainConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "graphNodeUrl", "http://graph");
        org.springframework.test.util.ReflectionTestUtils.setField(chain, "graphSubgraphName", "registerwerk");
        return chain;
    }

    @Test
    @DisplayName("T3-20: ERC-3525 nominal is the sum of slot VALUES per owner, not the number of token ids")
    void erc3525NominalFromSlotValues() {
        var chain = graphChain();
        givenErc3525Deployment(chain);
        when(graphNodeClient.fetchErc3525OwnerSlotBalances(chain, "0xToken")).thenReturn(List.of(
                new de.makibytes.registerwerk.indexer.internal.GraphNodeClient.Erc3525OwnerSlotBalance(
                        "0xAAA1", "1", new java.math.BigInteger("1000"), "EVENT_DERIVED"),
                new de.makibytes.registerwerk.indexer.internal.GraphNodeClient.Erc3525OwnerSlotBalance(
                        "0xaaa1", "2", new java.math.BigInteger("250"), "EVENT_DERIVED")));
        AssetHolder h = holder("0xaaa1", "2");
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(h.getNominalAmount()).isEqualByComparingTo("1250");
        verify(tokenTransferRepository, never()).findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("P4-01: an ERC-3525 deployment without a Graph Node BLOCKS the sync instead of being skipped")
    void erc3525UnindexedChainBlocks() {
        var chain = new de.makibytes.registerwerk.chain.api.ChainConfig(); // no Graph Node configured
        givenErc3525Deployment(chain);
        AssetHolder h = holder("0xaaa1", "500");
        h.setChainDerived(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("is not indexed");

        assertThat(h.getNominalAmount()).isEqualByComparingTo("500");
        verify(assetHolderRepository, never()).save(any());
        verify(holderSyncStatusPort, never()).markReconciled(any(), any());
    }

    @Test
    @DisplayName("P4-01: a deployment without indexing evidence BLOCKS the sync and never marks the register reconciled")
    void notIndexedDeploymentBlocksInsteadOfReconciling() {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setContractAddress("So1anaMint");
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        when(indexingCoverage.evaluate(deployment)).thenReturn(IndexingCoverage.Coverage.notIndexed("mint is not tracked"));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), eq(List.of()), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("is not indexed: mint is not tracked");

        verify(holderSyncStatusPort).markBlocked(eq(assetId), any(), eq(List.of()), any());
        verify(holderSyncStatusPort, never()).markReconciled(any(), any());
        verify(tokenTransferRepository, never()).findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(any(), any(), any());
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @DisplayName("P4-04: a negative net balance BLOCKS the sync; it is not clamped to zero")
    void negativeNetBalanceBlocks() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // A -> B with no mint anywhere in the indexed history: A nets to -300.
        givenTransfers(transfer("0xAAA1", "0xBBB2", "300", t0));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), eq(List.of("0xAAA1")), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("negative net balance");

        verify(holderSyncStatusPort).markBlocked(eq(assetId), any(), eq(List.of("0xAAA1")), any());
        verify(holderSyncStatusPort, never()).markReconciled(any(), any());
        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @DisplayName("T3-20: an INCOMPLETE ERC-3525 projection BLOCKS the sync")
    void erc3525IncompleteProjectionBlocks() {
        var chain = graphChain();
        givenErc3525Deployment(chain);
        when(graphNodeClient.fetchErc3525OwnerSlotBalances(chain, "0xToken")).thenReturn(List.of(
                new de.makibytes.registerwerk.indexer.internal.GraphNodeClient.Erc3525OwnerSlotBalance(
                        "0xaaa1", "1", new java.math.BigInteger("1000"), "INCOMPLETE")));
        when(holderSyncStatusPort.markBlocked(eq(assetId), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.syncHoldersFromBlockchain(assetId))
                .isInstanceOf(UnmappedHolderIdentityException.class)
                .hasMessageContaining("projection incomplete");
        verify(assetHolderRepository, never()).save(any());
    }
}
