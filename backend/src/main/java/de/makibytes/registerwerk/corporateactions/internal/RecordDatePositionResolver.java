package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.indexer.api.RegisterAsOfQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who held how much at the record-date cut-off (T3-06) — the input to the entitlement snapshot.
 * Previously the snapshot read the <em>live</em> register whenever the job happened to run, so a
 * transfer after the record date (or a late snapshot) moved entitlements.
 *
 * <ul>
 *   <li>Chain-deployed asset: the as-of balances from {@link RegisterAsOfQuery} (FINALIZED
 *       transfers before the cut-off, including transfers not yet linked to a deployment, H7). Each wallet with a
 *       positive balance is mapped to the holder row that was active at the cut-off, for investor and holder kind.
 *       <strong>Fail closed (H7):</strong> there is no "latest row" fallback. A wallet with no row active at the
 *       cut-off blocks the snapshot ("unmapped at record date"), unless every register entry of the wallet that was
 *       never closed before the cut-off names the same investor and holder kind - the entry merely postdates the
 *       cut-off (the holder sync created it minutes after the record date), so the attribution is unambiguous; that
 *       case is recorded in the snapshot notes. Entries that disagree, or that were closed before the cut-off,
 *       block it, as do not-yet-final transfers before the cut-off.</li>
 *   <li>Off-chain asset, and off-chain entries ({@code chainDerived == false}) of a chain asset
 *       whose wallet has no as-of chain balance: the trigger-maintained position history.</li>
 * </ul>
 * Rows with a zero as-of position get no entry.
 */
@Component
class RecordDatePositionResolver {

    private static final Logger log = LoggerFactory.getLogger(RecordDatePositionResolver.class);

    record Position(UUID assetHolderId, UUID investorId, String walletAddress, HolderKind holderKind,
                    BigDecimal nominal) {
    }

    /** Either positions (with operator notes) or the reason the snapshot must wait. */
    record Resolution(List<Position> positions, Optional<String> blockedReason, List<String> notes) {

        static Resolution blocked(String reason) {
            return new Resolution(List.of(), Optional.of(reason), List.of());
        }
    }

    private final AssetHolderRepository holderRepository;
    private final RegisterAsOfQuery registerAsOfQuery;
    private final HolderPositionHistoryReader historyReader;

    RecordDatePositionResolver(AssetHolderRepository holderRepository,
                               RegisterAsOfQuery registerAsOfQuery,
                               HolderPositionHistoryReader historyReader) {
        this.holderRepository = holderRepository;
        this.registerAsOfQuery = registerAsOfQuery;
        this.historyReader = historyReader;
    }

    Resolution resolve(UUID assetId, Instant cutoff) {
        List<AssetHolder> rows = holderRepository.findByAssetId(assetId);
        List<Position> positions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<AssetHolder> historyRows = rows;

        if (registerAsOfQuery.isChainDeployed(assetId)) {
            RegisterAsOfQuery.AsOfBalances asOf = registerAsOfQuery.balancesAsOf(assetId, cutoff);
            if (asOf.unsupportedReason() != null) {
                return Resolution.blocked("Record-date position cannot be determined: " + asOf.unsupportedReason());
            }
            if (asOf.unfinalizedBeforeCutoff() > 0) {
                return Resolution.blocked(asOf.unfinalizedBeforeCutoff() + " transfer(s) before the record-date "
                        + "cut-off " + cutoff + " are not final yet — not yet indexed past the record date.");
            }
            Map<String, List<AssetHolder>> byWallet = new HashMap<>();
            for (AssetHolder row : rows) {
                if (row.getWalletAddress() != null) {
                    byWallet.computeIfAbsent(row.getWalletAddress().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
                }
            }
            List<String> unmapped = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> balance : asOf.balances().entrySet()) {
                if (balance.getValue().signum() <= 0) {
                    continue;
                }
                RowAt resolved = rowAt(byWallet.get(balance.getKey()), cutoff);
                if (resolved.row() == null) {
                    unmapped.add(balance.getKey() + " (" + resolved.problem() + ")");
                    continue;
                }
                if (resolved.note() != null) {
                    notes.add("wallet " + balance.getKey() + ": " + resolved.note());
                }
                AssetHolder row = resolved.row();
                positions.add(new Position(row.getId(), row.getInvestorId(), row.getWalletAddress(),
                        row.getHolderKind(), balance.getValue()));
            }
            if (asOf.unlinkedAttributed() > 0) {
                notes.add(asOf.unlinkedAttributed() + " transfer(s) not yet linked to a deployment were attributed to "
                        + "this asset by asset id / contract address and counted in the record-date positions");
            }
            if (!unmapped.isEmpty()) {
                unmapped.sort(Comparator.naturalOrder());
                return Resolution.blocked("Wallet(s) holding units at the record-date cut-off " + cutoff
                        + " are unmapped at record date (no register entry can be attributed to them as of the record date): " + unmapped
                        + ". Map the wallet(s) or register the pool address, then the snapshot is retried.");
            }
            historyRows = rows.stream()
                    .filter(row -> !row.isChainDerived())
                    .filter(row -> row.getWalletAddress() == null
                            || !asOf.balances().containsKey(row.getWalletAddress().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (!historyRows.isEmpty()) {
            Map<UUID, HolderPositionHistoryReader.HistoricPosition> history = historyReader.positionsAsOf(assetId, cutoff);
            boolean backfilledUsed = false;
            for (AssetHolder row : historyRows) {
                HolderPositionHistoryReader.HistoricPosition h = history.get(row.getId());
                if (h == null || !h.activeAt(cutoff) || h.nominalAmount() == null || h.nominalAmount().signum() <= 0) {
                    continue;
                }
                if (h.backfilled() && cutoff.isBefore(h.recordedAt())) {
                    backfilledUsed = true;
                }
                positions.add(new Position(row.getId(), h.investorId(), h.walletAddress(), h.holderKind(),
                        h.nominalAmount()));
            }
            if (backfilledUsed) {
                String note = "record date precedes the position history (V13): off-chain positions changed "
                        + "before the history existed are taken at their value when it was created";
                notes.add(note);
                log.warn("Snapshot for asset={} cutoff={}: {}", assetId, cutoff, note);
            }
        }
        return new Resolution(positions, Optional.empty(), notes);
    }

    /** The attributable row for a wallet (or why there is none), plus an operator note when it postdates the cut-off. */
    private record RowAt(AssetHolder row, String note, String problem) {
        static RowAt of(AssetHolder row) {
            return new RowAt(row, null, null);
        }

        static RowAt noted(AssetHolder row, String note) {
            return new RowAt(row, note, null);
        }

        static RowAt none(String problem) {
            return new RowAt(null, null, problem);
        }
    }

    /**
     * H7, fail closed: the row that was active at the cut-off. No "newest row" fallback - a later row can name a
     * different holder than the one who held the wallet on the record date.
     */
    private static RowAt rowAt(List<AssetHolder> candidates, Instant cutoff) {
        if (candidates == null || candidates.isEmpty()) {
            return RowAt.none("no register entry");
        }
        List<AssetHolder> active = candidates.stream()
                .filter(r -> r.getCreatedAt() == null || r.getCreatedAt().isBefore(cutoff))
                .filter(r -> r.getRemovedAt() == null || r.getRemovedAt().isAfter(cutoff))
                .toList();
        if (!active.isEmpty()) {
            return sameHolder(active)
                    ? RowAt.of(active.getFirst())
                    : RowAt.none("several register entries active at the record date name different holders");
        }
        // Nothing was active at the cut-off. Entries closed before it do not count; an entry created after it (and
        // never closed before the cut-off) is attributable only when EVERY entry of the wallet - closed ones too -
        // names the same holder, i.e. it merely postdates the cut-off.
        List<AssetHolder> later = candidates.stream()
                .filter(r -> r.getRemovedAt() == null || r.getRemovedAt().isAfter(cutoff))
                .toList();
        if (later.isEmpty()) {
            return RowAt.none("its register entries were closed before the record date");
        }
        if (!sameHolder(candidates)) {
            return RowAt.none("its register entries name different holders and none was active at the record date");
        }
        AssetHolder row = later.stream()
                .min(Comparator.comparing(AssetHolder::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
        return RowAt.noted(row, "register entry " + row.getId() + " was created after the record date; attributed to "
                + "investor " + row.getInvestorId() + " because every entry of the wallet names the same holder");
    }

    private static boolean sameHolder(List<AssetHolder> rows) {
        AssetHolder first = rows.getFirst();
        return rows.stream().allMatch(r -> Objects.equals(r.getInvestorId(), first.getInvestorId())
                && r.getHolderKind() == first.getHolderKind());
    }
}
