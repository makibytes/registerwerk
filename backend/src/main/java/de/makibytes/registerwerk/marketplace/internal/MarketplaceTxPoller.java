package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Confirms onchain DappRegistry anchors: once an approved version's transaction lands,
 * the version turns PUBLISHED (superseding the previous one) and the listing goes live
 * in the catalog. A failed transaction clears the anchor so the operator can re-approve.
 *
 * <p>Also retries the anchor broadcast itself: {@code approve} schedules it only after
 * its own transaction commits (see {@link MarketplaceOnchainAnchorService}), so an
 * APPROVED version with no {@code onchainTx} yet means that after-commit broadcast
 * failed (RPC hiccup) — this poller re-submits it once the row is past the grace period.
 */
@Component
class MarketplaceTxPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceTxPoller.class);

    /** Rows younger than this are left to their own after-commit broadcast. */
    private static final Duration RETRY_GRACE = Duration.ofSeconds(90);

    private final DappVersionRepository versionRepository;
    private final DappListingRepository listingRepository;
    private final MarketplaceTxGateway txGateway;
    private final MarketplaceOnchainAnchorService anchorService;
    private final ChainEffectRecorder chainEffectRecorder;
    private final IsolatedTransactionExecutor isolatedTransactions;

    MarketplaceTxPoller(DappVersionRepository versionRepository,
                        DappListingRepository listingRepository,
                        MarketplaceTxGateway txGateway,
                        MarketplaceOnchainAnchorService anchorService,
                        ChainEffectRecorder chainEffectRecorder,
                        IsolatedTransactionExecutor isolatedTransactions) {
        this.versionRepository = versionRepository;
        this.listingRepository = listingRepository;
        this.txGateway = txGateway;
        this.anchorService = anchorService;
        this.chainEffectRecorder = chainEffectRecorder;
        this.isolatedTransactions = isolatedTransactions;
    }

    @SchedulerLock(name = "marketplaceTxPoller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 35_000)
    public void resolveApprovedVersions() {
        for (DappVersion version : versionRepository.findByStatus(DappVersionStatus.APPROVED)) {
            if (version.getOnchainTx() == null) {
                if (version.getReviewedAt() != null
                        && version.getReviewedAt().plus(RETRY_GRACE).isBefore(Instant.now())) {
                    AfterCommit.run(() -> anchorService.anchorApproval(version.getId()));
                }
                continue;
            }
            try {
                isolatedTransactions.run(() -> resolve(version));
            } catch (Exception e) {
                log.warn("Failed to resolve approved dApp version={}: {}", version.getId(), e.getMessage());
            }
        }
    }

    private void resolve(DappVersion version) {
        DappListing listing = listingRepository.findById(version.getListingId()).orElse(null);
        if (listing == null) return;

        String txHash = version.getOnchainTx();

        // Deliberately consults the same tracked, model-aware verdict BlockchainTransactionService
        // already computes for this tx (real finalized tag / confirmation depth / instant,
        // per the chain's configured FinalityModel) instead of independently accepting the first
        // mined receipt — a reorg that un-mines the anchor tx must not leave a listing PUBLISHED
        // on a state the chain has since abandoned.
        if (txGateway.isConfirmedFailure(txHash)) {
            log.error("DappRegistry tx={} failed for listing={}; clearing anchor for re-approval",
                    txHash, listing.getSlug());
            version.setOnchainTx(null);
            version.setAnchorChainConfigId(null);
            version.setAnchorBlockNumber(null);
            version.setAnchorBlockHash(null);
            version.setStatus(DappVersionStatus.IN_REVIEW);
            versionRepository.save(version);
            return;
        }
        if (!txGateway.isConfirmedSuccess(txHash)) {
            return; // still pending, or not yet final under the chain's FinalityModel
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location = txGateway.confirmedLocation(txHash);
        if (location.isEmpty()) {
            log.error("DappRegistry tx={} reported confirmed SUCCESS without durable block provenance; "
                    + "refusing to publish listing={}", txHash, listing.getSlug());
            return;
        }

        versionRepository.findByListingIdOrderByCreatedAtDesc(listing.getId()).stream()
                .filter(v -> v.getStatus() == DappVersionStatus.PUBLISHED)
                .forEach(v -> {
                    v.setStatus(DappVersionStatus.SUPERSEDED);
                    versionRepository.save(v);
                });

        version.setStatus(DappVersionStatus.PUBLISHED);
        version.setAnchorChainConfigId(location.get().chainConfigId());
        version.setAnchorBlockNumber(location.get().blockNumber());
        version.setAnchorBlockHash(location.get().blockHash());
        versionRepository.save(version);

        listing.setStatus(DappListingStatus.PUBLISHED);
        listing.setCurrentVersionId(version.getId());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        log.info("dApp {} v{} published (tx={})", listing.getSlug(), version.getVersion(), txHash);

        // A reorg retracting this anchor tx's confirming block must not leave the catalog
        // asserting a version as live that the chain no longer agrees was ever anchored — see
        // DappVersionRevertCompensator.
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "marketplace", DappVersionRevertCompensator.EFFECT_TYPE, "DappVersion", version.getId(),
                null, CompensationCategory.INVERSE_FLIP));
    }
}
