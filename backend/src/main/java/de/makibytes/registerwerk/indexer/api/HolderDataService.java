package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
@Service
public class HolderDataService implements de.makibytes.registerwerk.indexer.IndexerApi {

    private static final Logger log = LoggerFactory.getLogger(HolderDataService.class);

    private static final int PAGE_SIZE = 1_000;

    private final AssetDeploymentRepository deploymentRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HolderDataService(AssetDeploymentRepository deploymentRepository,
                             TokenTransferRepository tokenTransferRepository,
                             AssetHolderRepository assetHolderRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Synchronizes holder balances for one asset from the indexed transfer history. */
    @Transactional(noRollbackFor = UnmappedHolderIdentityException.class)
    public void syncHoldersFromBlockchain(UUID assetId) {
        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(assetId);
        if (deployments.isEmpty()) {
            log.debug("Holder sync for asset={}: no deployments, nothing to do", assetId);
            return;
        }

        // Net balance per wallet (case-insensitive key), preserving the on-chain casing
        // for display and the first incoming transfer for the acquisition date.
        Map<String, BigDecimal> balances = new HashMap<>();
        Map<String, String> displayAddress = new HashMap<>();
        Map<String, Instant> firstIncoming = new HashMap<>();

        long transferCount = 0;
        for (AssetDeployment deployment : deployments) {
            int pageNo = 0;
            Page<TokenTransfer> page;
            do {
                page = tokenTransferRepository.findByDeploymentIdAndFinalityStatusOrderByOccurredAtDesc(
                        deployment.getId(), FinalityLevel.FINALIZED, PageRequest.of(pageNo++, PAGE_SIZE));
                for (TokenTransfer t : page.getContent()) {
                    transferCount++;
                    BigDecimal amount = t.getAmount() != null ? t.getAmount() : BigDecimal.ONE;
                    apply(balances, displayAddress, t.getFromAddress(), amount.negate(), null, firstIncoming);
                    apply(balances, displayAddress, t.getToAddress(), amount, t.getOccurredAt(), firstIncoming);
                }
            } while (page.hasNext());
        }

        Map<String, AssetHolder> existingByWallet = new HashMap<>();
        // Deliberately unfiltered (includes soft-deleted/removed rows): this is a reconciliation
        // pass against on-chain truth by wallet address, and matching a removed row here (rather
        // than not finding it and creating a duplicate for the same wallet) is the correct
        // behavior — it surfaces as a balance update on a closed-out row instead of a phantom
        // second holder for the same address.
        assetHolderRepository.findByAssetId(assetId, org.springframework.data.domain.Pageable.unpaged())
                .forEach(h -> {
                    if (h.getWalletAddress() != null) {
                        existingByWallet.put(h.getWalletAddress().toLowerCase(Locale.ROOT), h);
                    }
                });

        List<String> unmappedWallets = balances.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .map(Map.Entry::getKey)
                .filter(wallet -> !existingByWallet.containsKey(wallet))
                .sorted()
                .toList();
        if (!unmappedWallets.isEmpty()) {
            // investor_id is mandatory register content and cannot be inferred from a transfer
            // address alone.  The previous code built an AssetHolder without investorId, which
            // failed at the NOT NULL/FK constraint after potentially modifying other holders in
            // the same pass. Fail before any writes: reorg compensation then becomes
            // COMPENSATION_FAILED and freezes the affected asset instead of publishing a
            // partially reconciled securities register.
            throw new UnmappedHolderIdentityException(assetId, unmappedWallets);
        }

        int updated = 0;
        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            BigDecimal balance = entry.getValue().max(BigDecimal.ZERO);
            AssetHolder holder = existingByWallet.get(entry.getKey());
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
        // must still zero out every previously chain-derived holder, not silently no-op.
        int zeroed = 0;
        for (Map.Entry<String, AssetHolder> existing : existingByWallet.entrySet()) {
            AssetHolder holder = existing.getValue();
            if (holder.isChainDerived() && !balances.containsKey(existing.getKey())
                    && holder.getNominalAmount() != null && holder.getNominalAmount().signum() != 0) {
                holder.setNominalAmount(BigDecimal.ZERO);
                assetHolderRepository.save(holder);
                zeroed++;
                eventPublisher.publishEvent(new HolderBalanceSyncedEvent(holder.getId(), assetId, false, BigDecimal.ZERO));
            }
        }

        log.info("Holder sync for asset={}: {} deployments, {} transfers → {} holders updated, "
                        + "{} zeroed (vanished from chain)",
                assetId, deployments.size(), transferCount, updated, zeroed);
    }

    /** Manual refresh triggered by user action. */
    @Transactional
    public void manualRefreshIssuance(String assetId) {
        syncHoldersFromBlockchain(UUID.fromString(assetId));
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
