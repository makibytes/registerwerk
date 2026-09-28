package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.HolderSyncStatusPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import de.makibytes.registerwerk.indexer.events.HolderSyncBlockedEvent;
import de.makibytes.registerwerk.indexer.events.HolderSyncRestoredEvent;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Rebuilds {@link AssetHolder} balances for an asset from the indexed
 * {@code token_transfer} history (written by the Graph Node / Solana / Canton sync
 * services). Pure off-chain aggregation — no RPC round-trips: credits every incoming
 * transfer, debits every outgoing one, treating the zero address as the mint/burn
 * counterparty.
 *
 * <p>Holders that have never once been touched by this service ({@link AssetHolder#isChainDerived()}
 * {@code == false}) are left alone — those are off-chain register entries (onchain level
 * {@code NONE}) or manually maintained rows, and the chain is not authoritative for them. Every
 * wallet this service has ever created or updated is marked chain-derived, and from then on is
 * fully self-healing: a wallet still present in the counted set is updated to the on-chain net
 * balance (including down to zero); a previously chain-derived wallet that has <b>dropped out</b>
 * of the counted set entirely — every one of its transfers orphaned by a reorg, for instance — is
 * zeroed rather than left at its last-known stale balance. Zeroed rows are kept, not deleted,
 * because the eWpG register must retain holder history. This self-healing pass runs even when the
 * counted set is empty (e.g. every transfer for this asset was just orphaned): a full recompute
 * finding nothing left to count must still zero out every previously chain-derived holder, not
 * silently return early.
 *
 * <p>Only {@link FinalityLevel#FINALIZED} transfers are counted. Rows a reorg has knocked out
 * ({@code ORPHANED}, kept for audit by {@link de.makibytes.registerwerk.indexer.internal.ReorgGuard}
 * rather than deleted) must never move the register's balance, and rows still below the
 * configured confirmation depth ({@code PROVISIONAL} or {@code SAFE}) are not yet safe to treat
 * as authoritative either. Note this filter is deliberately <b>stricter</b> than
 * {@code ChainDriftDetectionJob}'s {@code <> 'ORPHANED'} — that job counts PROVISIONAL/SAFE rows
 * as well, which is deliberate there (drift detection wants to compare the not-yet-confirmed
 * on-chain balance against this fully-confirmed one as early as possible; it accepts occasional
 * false drift for lower detection latency), not a bug. {@code Dac8ExportService} used to share the
 * same loose filter — that was a real inconsistency, not a deliberate tradeoff, since a compliance
 * export has no equivalent reason to accept not-yet-final data; it has since been tightened to
 * {@code = 'FINALIZED'}, matching this service. This means a holder's balance can lag a submitted
 * transfer by up to the chain's confirmation depth; that lag is not currently surfaced to the UI as
 * a separate "pending" figure.
 *
 * <p>Every run persists the asset's reconciliation state through {@link HolderSyncStatusPort}
 * (T2-18): a refused run marks the asset BLOCKED with the unmapped wallets before throwing (the
 * {@code noRollbackFor} keeps that write), a completed run marks it OK and stamps
 * {@code last_successful_holder_sync_at}. The corporate-action snapshot gate and the operator
 * banner read that state; previously a refusal was only a WARN line in the scheduler log.
 *
 * <p>Phase 3 (K2) rules:
 * <ul>
 *   <li><b>Removed rows are history, never reconciliation targets (T3-17).</b> Only active rows
 *       ({@code removed_at IS NULL}) receive balances. A wallet with a positive balance whose only
 *       register entry is closed is refused like an unmapped wallet ("belongs to a closed register
 *       entry"): writing the balance onto the closed row used to make the asset look reconciled
 *       while the position sat outside the register. {@code idx_holder_wallet} is unique over
 *       active rows only (V11), so a wallet may carry one active row plus removed history rows.</li>
 *   <li><b>ERC-3525 value, not token count (T3-20).</b> A 3525 transfer moves a token id and
 *       carries no amount, so netting transfers counted token ids. For ERC-3525 the balances come
 *       from the subgraph's {@code Erc3525OwnerSlotBalance} projection, summed over slots per
 *       owner (interim: one register entry per wallet, see PARK T3-20). An {@code INCOMPLETE}
 *       projection row refuses the sync.</li>
 *   <li><b>Missing amount (T3-20).</b> Only ERC-721 legitimately emits transfers without an amount
 *       (one token id = one unit). For every other standard such a transfer refuses the sync
 *       instead of silently counting as 1.</li>
 *   <li><b>Off-chain rows on deployed assets (T3-09).</b> Active, non-chain-derived rows with a
 *       positive nominal are counted and persisted per asset (operator banner + gauge), not
 *       BLOCKED — prevention sits at the write paths.</li>
 * </ul>
 */
@Service
public class HolderDataService implements de.makibytes.registerwerk.indexer.IndexerApi {

    private static final Logger log = LoggerFactory.getLogger(HolderDataService.class);

    private static final int PAGE_SIZE = 1_000;

    /** Standards read from the ERC-3525 value projection instead of transfer netting. */
    private static final Set<TokenStandard> VALUE_PROJECTION_STANDARDS = EnumSet.of(TokenStandard.ERC3525);

    private final AssetDeploymentRepository deploymentRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final HolderSyncStatusPort holderSyncStatusPort;
    private final AssetLookupPort assetLookupPort;
    private final ChainConfigRepository chainConfigRepository;
    private final GraphNodeClient graphNodeClient;

    public HolderDataService(AssetDeploymentRepository deploymentRepository,
                             TokenTransferRepository tokenTransferRepository,
                             AssetHolderRepository assetHolderRepository,
                             ApplicationEventPublisher eventPublisher,
                             HolderSyncStatusPort holderSyncStatusPort,
                             AssetLookupPort assetLookupPort,
                             ChainConfigRepository chainConfigRepository,
                             GraphNodeClient graphNodeClient) {
        this.deploymentRepository = deploymentRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.eventPublisher = eventPublisher;
        this.holderSyncStatusPort = holderSyncStatusPort;
        this.assetLookupPort = assetLookupPort;
        this.chainConfigRepository = chainConfigRepository;
        this.graphNodeClient = graphNodeClient;
    }

    /** Synchronizes holder balances for one asset from the indexed transfer history. */
    @Transactional(noRollbackFor = UnmappedHolderIdentityException.class)
    public void syncHoldersFromBlockchain(UUID assetId) {
        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(assetId);
        if (deployments.isEmpty()) {
            log.debug("Holder sync for asset={}: no deployments, nothing to do", assetId);
            return;
        }
        TokenStandard standard = assetLookupPort.findById(assetId)
                .map(AssetLookupPort.AssetInfo::tokenStandard)
                .orElse(null);

        // Net balance per wallet (case-insensitive key), preserving the on-chain casing
        // for display and the first incoming transfer for the acquisition date.
        Map<String, BigDecimal> balances = new HashMap<>();
        Map<String, String> displayAddress = new HashMap<>();
        Map<String, Instant> firstIncoming = new HashMap<>();

        long transferCount = 0;
        boolean[] unindexedSkipped = {false};
        if (standard != null && VALUE_PROJECTION_STANDARDS.contains(standard)) {
            transferCount = collectErc3525Values(assetId, deployments, balances, displayAddress, unindexedSkipped);
        } else {
            long transfersWithoutAmount = 0;
            for (AssetDeployment deployment : deployments) {
                int pageNo = 0;
                Page<TokenTransfer> page;
                do {
                    page = tokenTransferRepository.findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(
                            deployment.getId(), FinalityLevel.FINALIZED, PageRequest.of(pageNo++, PAGE_SIZE));
                    for (TokenTransfer t : page.getContent()) {
                        transferCount++;
                        BigDecimal amount = t.getAmount();
                        if (amount == null) {
                            if (standard != TokenStandard.ERC721) {
                                transfersWithoutAmount++;
                                continue;
                            }
                            amount = BigDecimal.ONE;
                        }
                        apply(balances, displayAddress, t.getFromAddress(), amount.negate(), null, firstIncoming);
                        apply(balances, displayAddress, t.getToAddress(), amount, t.getOccurredAt(), firstIncoming);
                    }
                } while (page.hasNext());
            }
            if (transfersWithoutAmount > 0) {
                refuse(assetId, List.of(), transfersWithoutAmount + " finalized transfer(s) without amount on a "
                        + standard + " asset — only ERC-721 transfers may omit the amount");
            }
        }

        // T3-17: active and removed rows are keyed separately. idx_holder_wallet is unique over
        // active rows only (V11), so one wallet may have an active row and removed history rows;
        // a single map keyed by wallet would pick one of them nondeterministically.
        Map<String, AssetHolder> activeByWallet = new HashMap<>();
        Set<String> removedWallets = new TreeSet<>();
        assetHolderRepository.findByAssetId(assetId, org.springframework.data.domain.Pageable.unpaged())
                .forEach(h -> {
                    if (h.getWalletAddress() == null) {
                        return;
                    }
                    String key = h.getWalletAddress().toLowerCase(Locale.ROOT);
                    if (h.getRemovedAt() == null) {
                        activeByWallet.put(key, h);
                    } else {
                        removedWallets.add(key);
                    }
                });

        // T3-09: active rows the chain does not back — never chain-derived, not about to become
        // chain-derived in this run, positive nominal. Reported, not blocked.
        int offchainRows = (int) activeByWallet.entrySet().stream()
                .filter(e -> !e.getValue().isChainDerived() && !balances.containsKey(e.getKey()))
                .filter(e -> e.getValue().getNominalAmount() != null && e.getValue().getNominalAmount().signum() > 0)
                .count();
        holderSyncStatusPort.recordOffchainRows(assetId, offchainRows);

        List<String> positive = balances.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .map(Map.Entry::getKey)
                .filter(wallet -> !activeByWallet.containsKey(wallet))
                .sorted()
                .toList();
        List<String> closedEntryWallets = positive.stream().filter(removedWallets::contains).toList();
        List<String> unmappedWallets = positive.stream().filter(w -> !removedWallets.contains(w)).toList();
        if (!positive.isEmpty()) {
            // investor_id is mandatory register content and cannot be inferred from a transfer
            // address alone.  The previous code built an AssetHolder without investorId, which
            // failed at the NOT NULL/FK constraint after potentially modifying other holders in
            // the same pass. Fail before any writes: reorg compensation then becomes
            // COMPENSATION_FAILED and freezes the affected asset instead of publishing a
            // partially reconciled securities register.
            // The BLOCKED state is persisted (not just logged) so the operator banner, the
            // metric/alert and the corporate-action snapshot gate all see the stale register. A
            // pool contract (lending market, escrow, desk) is resolved by registering it as a
            // NOMINEE_POOL holder; an investor wallet by mapping it to its investor.
            // T3-17: a wallet whose only register entry is closed is refused the same way — the
            // balance must not be written onto the closed row (it would disappear from every
            // active-only view while the asset reported "reconciled").
            UnmappedHolderIdentityException refusal = closedEntryWallets.isEmpty()
                    ? new UnmappedHolderIdentityException(assetId, unmappedWallets)
                    : new UnmappedHolderIdentityException(assetId, describeRefusal(unmappedWallets, closedEntryWallets));
            if (holderSyncStatusPort.markBlocked(assetId, Instant.now(), positive, refusal.getMessage())) {
                eventPublisher.publishEvent(new HolderSyncBlockedEvent(assetId, positive));
            }
            throw refusal;
        }

        int updated = 0;
        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            BigDecimal balance = entry.getValue().max(BigDecimal.ZERO);
            AssetHolder holder = activeByWallet.get(entry.getKey());
            if (holder != null) {
                boolean balanceChanged = holder.getNominalAmount() == null
                        || holder.getNominalAmount().compareTo(balance) != 0;
                // A holder whose wallet appears in the current counted set is, by definition,
                // chain-derived going forward — even if this particular sync found no balance
                // change — so the reconciliation pass below can tell "this wallet is still on
                // chain, just unchanged" apart from "this wallet vanished from the chain".
                boolean newlyChainDerived = !holder.isChainDerived();
                if (balanceChanged || newlyChainDerived) {
                    holder.setNominalAmount(balance);
                    holder.setChainDerived(true);
                    assetHolderRepository.save(holder);
                }
                if (balanceChanged) {
                    updated++;
                    eventPublisher.publishEvent(new HolderBalanceSyncedEvent(holder.getId(), assetId, false, balance));
                }
            }
        }

        // Self-healing pass: a wallet whose transfers were all orphaned by a reorg (or that has
        // none left in the FINALIZED set for any other reason) drops out of `balances` entirely,
        // but its previously chain-derived, non-zero row must not be left stale forever — that was
        // the exact gap this fixes. Off-chain register entries (chainDerived == false) are left
        // untouched, matching this class's javadoc. Runs even when `balances` is empty (e.g. every
        // transfer for this asset was just orphaned) — a full recompute with nothing left to count
        // must still zero out every previously chain-derived holder, not silently no-op. Removed
        // rows are history and are never rewritten (T3-17).
        // An ERC-3525 deployment on an unindexed chain contributed nothing to `balances`; that is
        // "unknown", not "zero", so the pass must not wipe previously chain-derived holders.
        int zeroed = 0;
        if (unindexedSkipped[0]) {
            log.warn("Holder sync for asset={}: an ERC-3525 deployment is not indexed; "
                    + "skipping the vanished-holder zeroing pass", assetId);
        }
        for (Map.Entry<String, AssetHolder> existing : activeByWallet.entrySet()) {
            AssetHolder holder = existing.getValue();
            if (!unindexedSkipped[0] && holder.isChainDerived() && !balances.containsKey(existing.getKey())
                    && holder.getNominalAmount() != null && holder.getNominalAmount().signum() != 0) {
                holder.setNominalAmount(BigDecimal.ZERO);
                assetHolderRepository.save(holder);
                zeroed++;
                eventPublisher.publishEvent(new HolderBalanceSyncedEvent(holder.getId(), assetId, false, BigDecimal.ZERO));
            }
        }

        if (holderSyncStatusPort.markReconciled(assetId, Instant.now())) {
            eventPublisher.publishEvent(new HolderSyncRestoredEvent(assetId));
        }

        log.info("Holder sync for asset={}: {} deployments, {} transfers → {} holders updated, "
                        + "{} zeroed (vanished from chain), {} off-chain rows",
                assetId, deployments.size(), transferCount, updated, zeroed, offchainRows);
    }

    /** Manual refresh triggered by user action. Same {@code noRollbackFor} as the sync it wraps
     *  (self-invocation runs in this transaction): a refused refresh must still persist BLOCKED. */
    @Transactional(noRollbackFor = UnmappedHolderIdentityException.class)
    public void manualRefreshIssuance(String assetId) {
        syncHoldersFromBlockchain(UUID.fromString(assetId));
    }

    /**
     * T3-20: ERC-3525 balances from the subgraph's owner/slot value projection, summed over slots
     * per owner. Refuses (BLOCKED) when any projection row is {@code INCOMPLETE}; a Graph Node
     * outage propagates as a transient query failure (nothing written, not BLOCKED).
     *
     * @return number of projection rows read
     */
    private long collectErc3525Values(UUID assetId, List<AssetDeployment> deployments,
                                      Map<String, BigDecimal> balances, Map<String, String> displayAddress,
                                      boolean[] unindexedSkipped) {
        long rows = 0;
        List<String> incompleteOwners = new ArrayList<>();
        for (AssetDeployment deployment : deployments) {
            if (deployment.getContractAddress() == null || deployment.getContractAddress().isBlank()) {
                continue;
            }
            ChainConfig chain = deployment.getChainConfigId() == null ? null
                    : chainConfigRepository.findById(deployment.getChainConfigId()).orElse(null);
            if (chain == null || chain.getGraphNodeUrl() == null || chain.getGraphSubgraphName() == null) {
                // Same as the transfer path on an unindexed chain (nothing counted). Refusing here
                // would BLOCK every ERC-3525 asset on a chain without a Graph Node; the coverage
                // guard for unindexed deployments is Phase 4 work (T3-18), for all standards alike.
                log.warn("Holder sync for asset={}: ERC-3525 deployment {} has no Graph Node configured; "
                        + "its value projection is not counted", assetId, deployment.getId());
                unindexedSkipped[0] = true;
                continue;
            }
            for (GraphNodeClient.Erc3525OwnerSlotBalance row
                    : graphNodeClient.fetchErc3525OwnerSlotBalances(chain, deployment.getContractAddress())) {
                rows++;
                if (row.incomplete()) {
                    incompleteOwners.add(row.owner());
                    continue;
                }
                apply(balances, displayAddress, row.owner(), new BigDecimal(row.value()), null, new HashMap<>());
            }
        }
        if (!incompleteOwners.isEmpty()) {
            refuse(assetId, List.of(), "ERC-3525 value projection incomplete for owner(s) "
                    + incompleteOwners.stream().sorted().distinct().toList()
                    + " — replay the subgraph from the token's deployment block");
        }
        return rows;
    }

    /** Persists BLOCKED with {@code reason} and throws the pre-write refusal. */
    private void refuse(UUID assetId, List<String> wallets, String reason) {
        UnmappedHolderIdentityException refusal = new UnmappedHolderIdentityException(assetId, reason);
        if (holderSyncStatusPort.markBlocked(assetId, Instant.now(), wallets, refusal.getMessage())) {
            eventPublisher.publishEvent(new HolderSyncBlockedEvent(assetId, wallets));
        }
        throw refusal;
    }

    private static String describeRefusal(List<String> unmapped, List<String> closedEntry) {
        StringBuilder reason = new StringBuilder();
        if (!unmapped.isEmpty()) {
            reason.append("finalized transfers contain wallet(s) with no registered holder identity: ")
                    .append(unmapped).append("; ");
        }
        reason.append("wallet(s) belong to a closed register entry (removed holder) but hold a finalized "
                + "balance — register a new entry for the wallet: ").append(closedEntry);
        return reason.toString();
    }

    private static void apply(Map<String, BigDecimal> balances, Map<String, String> displayAddress,
                              String address, BigDecimal delta, Instant occurredAt,
                              Map<String, Instant> firstIncoming) {
        if (isMintBurnCounterparty(address)) {
            return;
        }
        String key = address.toLowerCase(Locale.ROOT);
        balances.merge(key, delta, BigDecimal::add);
        displayAddress.putIfAbsent(key, address);
        if (occurredAt != null) {
            firstIncoming.merge(key, occurredAt, (a, b) -> a.isBefore(b) ? a : b);
        }
    }

    /** Null, blank, or all-zero-hex addresses are the mint/burn side of an event, not a holder. */
    private static boolean isMintBurnCounterparty(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        String stripped = address.startsWith("0x") ? address.substring(2) : address;
        return stripped.chars().allMatch(c -> c == '0');
    }
}
