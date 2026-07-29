package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.shared.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

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

    MarketplaceTxPoller(DappVersionRepository versionRepository,
                        DappListingRepository listingRepository,
                        MarketplaceTxGateway txGateway,
                        MarketplaceOnchainAnchorService anchorService) {
        this.versionRepository = versionRepository;
        this.listingRepository = listingRepository;
        this.txGateway = txGateway;
        this.anchorService = anchorService;
    }

    @SchedulerLock(name = "marketplaceTxPoller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000)
    @Transactional
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
                resolve(version);
            } catch (Exception e) {
                log.warn("Failed to resolve approved dApp version={}: {}", version.getId(), e.getMessage());
            }
        }
    }

    private void resolve(DappVersion version) {
        DappListing listing = listingRepository.findById(version.getListingId()).orElse(null);
        if (listing == null) return;

        txGateway.receipt(listing.getChainConfigId(), version.getOnchainTx()).ifPresent(receipt -> {
            if (!"0x1".equals(receipt.getStatus())) {
                log.error("DappRegistry tx={} failed for listing={}; clearing anchor for re-approval",
                        version.getOnchainTx(), listing.getSlug());
                version.setOnchainTx(null);
                version.setStatus(DappVersionStatus.IN_REVIEW);
                versionRepository.save(version);
                return;
            }

            versionRepository.findByListingIdOrderByCreatedAtDesc(listing.getId()).stream()
                    .filter(v -> v.getStatus() == DappVersionStatus.PUBLISHED)
                    .forEach(v -> {
                        v.setStatus(DappVersionStatus.SUPERSEDED);
                        versionRepository.save(v);
                    });

            version.setStatus(DappVersionStatus.PUBLISHED);
            versionRepository.save(version);

            listing.setStatus(DappListingStatus.PUBLISHED);
            listing.setCurrentVersionId(version.getId());
            listing.setUpdatedAt(Instant.now());
            listingRepository.save(listing);
            log.info("dApp {} v{} published (tx={})", listing.getSlug(), version.getVersion(), version.getOnchainTx());
        });
    }
}
