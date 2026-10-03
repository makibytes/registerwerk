package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.indexer.api.RegisterAsOfQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T3-06: the snapshot uses positions as of the cut-off, not the live register. */
@DisplayName("RecordDatePositionResolver unit tests")
class RecordDatePositionResolverTest {

    private static final Instant CUTOFF = Instant.parse("2025-06-27T22:00:00Z");
    private final UUID assetId = UUID.randomUUID();
    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final RegisterAsOfQuery asOf = mock(RegisterAsOfQuery.class);
    private final HolderPositionHistoryReader history = mock(HolderPositionHistoryReader.class);
    private final RecordDatePositionResolver resolver = new RecordDatePositionResolver(holders, asOf, history);

    private AssetHolder holder(String wallet, BigDecimal liveNominal, boolean chainDerived) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
        h.setAssetId(assetId);
        h.setInvestorId(UUID.randomUUID());
        h.setWalletAddress(wallet);
        h.setNominalAmount(liveNominal);
        h.setChainDerived(chainDerived);
        ReflectionTestUtils.setField(h, "createdAt", CUTOFF.minusSeconds(86400 * 30));
        return h;
    }

    @Test
    @DisplayName("snapshotUsesBalancesAsOfRecordDate: a transfer after the cut-off does not move the entitlement")
    void snapshotUsesBalancesAsOfRecordDate() {
        AssetHolder seller = holder("0xaaa", BigDecimal.ZERO, true);   // live: sold everything after the record date
        AssetHolder buyer = holder("0xbbb", new BigDecimal("100"), true);
        when(holders.findByAssetId(assetId)).thenReturn(List.of(seller, buyer));
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(
                new RegisterAsOfQuery.AsOfBalances(Map.of("0xaaa", new BigDecimal("100")), 0));

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.blockedReason()).isEmpty();
        assertThat(result.positions()).singleElement().satisfies(p -> {
            assertThat(p.assetHolderId()).isEqualTo(seller.getId());
            assertThat(p.nominal()).isEqualByComparingTo("100");
        });
    }

    @Test
    @DisplayName("a wallet with units at the cut-off but no register row blocks the snapshot")
    void unmappedWalletBlocks() {
        when(holders.findByAssetId(assetId)).thenReturn(List.of());
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(
                new RegisterAsOfQuery.AsOfBalances(Map.of("0xccc", BigDecimal.TEN), 0));

        assertThat(resolver.resolve(assetId, CUTOFF).blockedReason()).hasValueSatisfying(
                r -> assertThat(r).contains("unmapped at record date").contains("0xccc"));
    }

    @Test
    @DisplayName("transfers before the cut-off that are not final yet block the snapshot")
    void unfinalizedTransfersBlock() {
        when(holders.findByAssetId(assetId)).thenReturn(List.of());
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(new RegisterAsOfQuery.AsOfBalances(Map.of(), 2));

        assertThat(resolver.resolve(assetId, CUTOFF).blockedReason()).hasValueSatisfying(
                r -> assertThat(r).contains("not yet indexed past the record date"));
    }

    @Test
    @DisplayName("offchainHistoryAsOf: an off-chain register uses the position history, not the live nominal")
    void offchainHistoryAsOf() {
        AssetHolder h = holder("0xddd", new BigDecimal("999"), false); // live value changed after the record date
        when(holders.findByAssetId(assetId)).thenReturn(List.of(h));
        when(asOf.isChainDeployed(assetId)).thenReturn(false);
        when(history.positionsAsOf(assetId, CUTOFF)).thenReturn(Map.of(h.getId(),
                new HolderPositionHistoryReader.HistoricPosition(h.getId(), h.getInvestorId(), "0xddd",
                        HolderKind.INVESTOR, new BigDecimal("40"), null, false, CUTOFF.minusSeconds(1000))));

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.positions()).singleElement()
                .satisfies(p -> assertThat(p.nominal()).isEqualByComparingTo("40"));
    }

    @Test
    @DisplayName("a holder removed before the cut-off gets no entry")
    void removedBeforeCutoffHasNoEntry() {
        AssetHolder h = holder("0xeee", new BigDecimal("5"), false);
        when(holders.findByAssetId(assetId)).thenReturn(List.of(h));
        when(asOf.isChainDeployed(assetId)).thenReturn(false);
        when(history.positionsAsOf(any(), any())).thenReturn(Map.of(h.getId(),
                new HolderPositionHistoryReader.HistoricPosition(h.getId(), h.getInvestorId(), "0xeee",
                        HolderKind.INVESTOR, new BigDecimal("5"), CUTOFF.minusSeconds(5), false, CUTOFF)));

        assertThat(resolver.resolve(assetId, CUTOFF).positions()).isEmpty();
    }

    @Test
    @DisplayName("an unsupported as-of position (ERC-3525 / null-amount transfers) blocks the snapshot")
    void unsupportedAsOfBlocks() {
        when(holders.findByAssetId(assetId)).thenReturn(List.of());
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(
                new RegisterAsOfQuery.AsOfBalances(Map.of("0xaaa", BigDecimal.ONE), 0, "cannot be reconstructed"));

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.positions()).isEmpty();
        assertThat(result.blockedReason()).hasValueSatisfying(r -> assertThat(r).contains("cannot be reconstructed"));
    }

    // ── Wave 0b H7: fail closed, no "newest row" fallback ──────────────────────

    private AssetHolder row(String wallet, UUID investor, Instant createdAt, Instant removedAt) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
        h.setAssetId(assetId);
        h.setInvestorId(investor);
        h.setWalletAddress(wallet);
        h.setNominalAmount(BigDecimal.ZERO);
        h.setChainDerived(true);
        ReflectionTestUtils.setField(h, "createdAt", createdAt);
        h.setRemovedAt(removedAt);
        return h;
    }

    private void walletHoldsAtCutoff(String wallet, AssetHolder... rows) {
        when(holders.findByAssetId(assetId)).thenReturn(List.of(rows));
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(
                new RegisterAsOfQuery.AsOfBalances(Map.of(wallet, new BigDecimal("100")), 0));
    }

    @Test
    @DisplayName("H7: a wallet whose earlier holder was closed before the cut-off and whose later row names another holder is NOT attributed to the newest row")
    void noNewestRowFallbackWhenHoldersDiffer() {
        AssetHolder closedBefore = row("0xaaa", UUID.randomUUID(), CUTOFF.minusSeconds(86400 * 90), CUTOFF.minusSeconds(86400 * 10));
        AssetHolder createdAfter = row("0xaaa", UUID.randomUUID(), CUTOFF.plusSeconds(3600), null);
        walletHoldsAtCutoff("0xaaa", closedBefore, createdAfter);

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.positions()).isEmpty();
        assertThat(result.blockedReason()).hasValueSatisfying(r -> assertThat(r)
                .contains("unmapped at record date").contains("0xaaa"));
    }

    @Test
    @DisplayName("H7: the only register entry of the wallet was closed before the cut-off: blocked, not attributed to the closed entry")
    void closedBeforeCutoffIsNotAttributed() {
        AssetHolder closed = row("0xbbb", UUID.randomUUID(), CUTOFF.minusSeconds(86400 * 90), CUTOFF.minusSeconds(86400));
        walletHoldsAtCutoff("0xbbb", closed);

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.positions()).isEmpty();
        assertThat(result.blockedReason()).hasValueSatisfying(r -> assertThat(r).contains("closed before the record date"));
    }

    @Test
    @DisplayName("H7: the active row at the cut-off wins even when a later row exists for the wallet")
    void activeRowAtCutoffWins() {
        AssetHolder atCutoff = row("0xccc", UUID.randomUUID(), CUTOFF.minusSeconds(86400 * 20), CUTOFF.plusSeconds(86400));
        AssetHolder later = row("0xccc", UUID.randomUUID(), CUTOFF.plusSeconds(86400), null);
        walletHoldsAtCutoff("0xccc", later, atCutoff);

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.positions()).singleElement()
                .satisfies(p -> assertThat(p.assetHolderId()).isEqualTo(atCutoff.getId()));
        assertThat(result.notes()).isEmpty();
    }

    @Test
    @DisplayName("H7: an entry created minutes after the cut-off (holder sync lag) is attributed only because it is unambiguous, and says so")
    void entryCreatedAfterCutoffIsAttributedWhenUnambiguous() {
        AssetHolder lagging = row("0xddd", UUID.randomUUID(), CUTOFF.plusSeconds(300), null);
        walletHoldsAtCutoff("0xddd", lagging);

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.blockedReason()).isEmpty();
        assertThat(result.positions()).singleElement()
                .satisfies(p -> assertThat(p.investorId()).isEqualTo(lagging.getInvestorId()));
        assertThat(result.notes()).singleElement().satisfies(n -> assertThat(n)
                .contains("created after the record date").contains("same holder"));
    }

    @Test
    @DisplayName("H7: transfers that were not yet linked to a deployment are reported in the snapshot notes")
    void unlinkedTransfersAreNoted() {
        AssetHolder holder = row("0xeee", UUID.randomUUID(), CUTOFF.minusSeconds(86400), null);
        when(holders.findByAssetId(assetId)).thenReturn(List.of(holder));
        when(asOf.isChainDeployed(assetId)).thenReturn(true);
        when(asOf.balancesAsOf(assetId, CUTOFF)).thenReturn(new RegisterAsOfQuery.AsOfBalances(
                Map.of("0xeee", new BigDecimal("5")), 0, null, 3));

        var result = resolver.resolve(assetId, CUTOFF);

        assertThat(result.notes()).singleElement().satisfies(n -> assertThat(n)
                .contains("3 transfer(s) not yet linked to a deployment"));
    }
}
