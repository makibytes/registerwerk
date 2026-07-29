package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * <p>Holders whose wallet never appears in the transfer history are left untouched —
 * those are off-chain register entries (onchain level {@code NONE}) or manually
 * maintained rows, and the chain is not authoritative for them. Holders whose wallet
 * does appear are updated to the on-chain net balance, including down to zero; zeroed
 * rows are kept, not deleted, because the eWpG register must retain holder history.
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
    @Transactional
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
                page = tokenTransferRepository.findByDeploymentIdOrderByOccurredAtDesc(
                        deployment.getId(), PageRequest.of(pageNo++, PAGE_SIZE));
                for (TokenTransfer t : page.getContent()) {
                    transferCount++;
                    BigDecimal amount = t.getAmount() != null ? t.getAmount() : BigDecimal.ONE;
                    apply(balances, displayAddress, t.getFromAddress(), amount.negate(), null, firstIncoming);
                    apply(balances, displayAddress, t.getToAddress(), amount, t.getOccurredAt(), firstIncoming);
                }
            } while (page.hasNext());
        }

        if (balances.isEmpty()) {
            log.info("Holder sync for asset={}: {} deployments, no indexed transfers yet",
                    assetId, deployments.size());
            return;
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

        int created = 0;
        int updated = 0;
        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            BigDecimal balance = entry.getValue().max(BigDecimal.ZERO);
            AssetHolder holder = existingByWallet.get(entry.getKey());
            if (holder != null) {
                if (holder.getNominalAmount() == null || holder.getNominalAmount().compareTo(balance) != 0) {
                    holder.setNominalAmount(balance);
                    assetHolderRepository.save(holder);
                    updated++;
                    eventPublisher.publishEvent(new HolderBalanceSyncedEvent(holder.getId(), assetId, false, balance));
                }
            } else if (balance.signum() > 0) {
                AssetHolder fresh = new AssetHolder();
                fresh.setAssetId(assetId);
                fresh.setWalletAddress(displayAddress.get(entry.getKey()));
                fresh.setNominalAmount(balance);
                Instant acquired = firstIncoming.get(entry.getKey());
                fresh.setAcquisitionDate(acquired != null
                        ? LocalDate.ofInstant(acquired, ZoneOffset.UTC)
                        : LocalDate.now(ZoneOffset.UTC));
                AssetHolder saved = assetHolderRepository.save(fresh);
                created++;
                eventPublisher.publishEvent(new HolderBalanceSyncedEvent(saved.getId(), assetId, true, balance));
            }
        }

        log.info("Holder sync for asset={}: {} deployments, {} transfers → {} holders created, {} updated",
                assetId, deployments.size(), transferCount, created, updated);
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
