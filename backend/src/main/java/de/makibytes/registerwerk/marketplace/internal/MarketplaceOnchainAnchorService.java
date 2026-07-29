package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermission;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcasts DappRegistry anchor transactions <em>after</em> the review outcome has been
 * committed (see {@link de.makibytes.registerwerk.shared.AfterCommit}): the committed
 * APPROVED version (or DEPRECATED/DELISTED listing) is the source of intent; this
 * component turns it into the chain transaction in a fresh transaction. Idempotent —
 * versions that already carry an anchor tx are skipped — so {@link MarketplaceTxPoller}
 * can retry versions whose broadcast failed. Failures are logged, never thrown.
 */
@Component
public class MarketplaceOnchainAnchorService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceOnchainAnchorService.class);

    private final DappVersionRepository versionRepository;
    private final DappListingRepository listingRepository;
    private final DappRequiredPermissionRepository requiredPermissionRepository;
    private final OrgRegistrationRepository orgRegistrationRepository;
    private final MarketplaceTxGateway txGateway;

    MarketplaceOnchainAnchorService(
            DappVersionRepository versionRepository,
            DappListingRepository listingRepository,
            DappRequiredPermissionRepository requiredPermissionRepository,
            OrgRegistrationRepository orgRegistrationRepository,
            MarketplaceTxGateway txGateway) {
        this.versionRepository = versionRepository;
        this.listingRepository = listingRepository;
        this.requiredPermissionRepository = requiredPermissionRepository;
        this.orgRegistrationRepository = orgRegistrationRepository;
        this.txGateway = txGateway;
    }

    /** Anchors a committed APPROVED version onchain (registerDapp / updateManifest). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void anchorApproval(UUID versionId) {
        versionRepository.findById(versionId).ifPresent(version -> {
            if (version.getStatus() != DappVersionStatus.APPROVED || version.getOnchainTx() != null) {
                return;
            }
            DappListing listing = listingRepository.findById(version.getListingId()).orElse(null);
            if (listing == null) {
                return;
            }
            OrgRegistration publisherOrg = orgRegistrationRepository
                    .findByLegalEntityIdAndChainConfigId(listing.getPublisherEntityId(), listing.getChainConfigId())
                    .orElse(null);
            if (publisherOrg == null) {
                log.error("No org registration for publisher of listing={}; cannot anchor", listing.getSlug());
                return;
            }

            List<DappRequiredPermission> required = requiredPermissionRepository.findByVersionId(version.getId());
            List<String> permissionCodes = required.stream()
                    .map(DappRequiredPermission::getPermissionCode)
                    .toList();
            List<Long> claimTopics = required.stream()
                    .flatMap(permission -> permission.getClaimTopics().stream())
                    .distinct()
                    .toList();
            byte[] manifestHash = Numeric.hexStringToByteArray(version.getManifestHash());

            boolean firstPublication = versionRepository
                    .findByListingIdOrderByCreatedAtDesc(listing.getId()).stream()
                    .noneMatch(v -> v.getStatus() == DappVersionStatus.PUBLISHED
                            || v.getStatus() == DappVersionStatus.SUPERSEDED);
            try {
                String txHash = firstPublication
                        ? txGateway.submitToDappRegistry(listing.getChainConfigId(),
                                DappRegistryAbi.registerDapp(listing.getSlug(), publisherOrg.getOrgAddress(),
                                        manifestHash, version.getVersion(), permissionCodes, claimTopics),
                                Map.of("slug", listing.getSlug(), "manifestHash", version.getManifestHash()))
                        : txGateway.submitToDappRegistry(listing.getChainConfigId(),
                                DappRegistryAbi.updateManifest(listing.getSlug(), manifestHash,
                                        version.getVersion()),
                                Map.of("slug", listing.getSlug(), "manifestHash", version.getManifestHash()));
                version.setOnchainTx(txHash);
                versionRepository.save(version);
            } catch (Exception e) {
                log.warn("DappRegistry anchor broadcast failed for version={} (poller will retry): {}",
                        versionId, e.getMessage());
            }
        });
    }

    /** Best-effort {@code setStatus} mirror of a committed DEPRECATED/DELISTED listing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastLifecycleStatus(UUID listingId) {
        listingRepository.findById(listingId).ifPresent(listing -> {
            int onchainStatus;
            if (listing.getStatus() == DappListingStatus.DEPRECATED) {
                onchainStatus = DappRegistryAbi.STATUS_DEPRECATED;
            } else if (listing.getStatus() == DappListingStatus.DELISTED) {
                onchainStatus = DappRegistryAbi.STATUS_SUSPENDED;
            } else {
                return;
            }
            try {
                txGateway.submitToDappRegistry(listing.getChainConfigId(),
                        DappRegistryAbi.setStatus(listing.getSlug(), onchainStatus),
                        Map.of("slug", listing.getSlug(), "status", listing.getStatus().name()));
            } catch (Exception e) {
                log.error("DappRegistry setStatus broadcast failed for listing={} — the operator must "
                        + "re-apply the lifecycle change if the onchain status stays ahead: {}",
                        listing.getSlug(), e.getMessage());
            }
        });
    }
}
