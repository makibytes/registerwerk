package de.makibytes.registerwerk.marketplace.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.marketplace.internal.ListingLifecycleService;
import de.makibytes.registerwerk.marketplace.internal.PaymentMethodResolver;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ListingResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.RejectRequest;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.RequiredPermissionResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ReviewDetailResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ReviewNotesRequest;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ReviewQueueItem;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.VersionResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operator review queue + published-catalog administration (4-eyes on approval). */
@RestController
@RequestMapping("/api/v1/marketplace")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class MarketplaceAdminController {

    private final ListingLifecycleService lifecycleService;
    private final DappListingRepository listingRepository;
    private final DappVersionRepository versionRepository;
    private final DappRequiredPermissionRepository requiredPermissionRepository;
    private final PaymentMethodResolver paymentMethodResolver;
    private final LegalEntityRepository legalEntityRepository;
    private final ChainConfigRepository chainConfigRepository;

    public MarketplaceAdminController(
            ListingLifecycleService lifecycleService,
            DappListingRepository listingRepository,
            DappVersionRepository versionRepository,
            DappRequiredPermissionRepository requiredPermissionRepository,
            PaymentMethodResolver paymentMethodResolver,
            LegalEntityRepository legalEntityRepository,
            ChainConfigRepository chainConfigRepository) {
        this.lifecycleService = lifecycleService;
        this.listingRepository = listingRepository;
        this.versionRepository = versionRepository;
        this.requiredPermissionRepository = requiredPermissionRepository;
        this.paymentMethodResolver = paymentMethodResolver;
        this.legalEntityRepository = legalEntityRepository;
        this.chainConfigRepository = chainConfigRepository;
    }

    // ── Review queue ──────────────────────────────────────────────────────────

    @GetMapping("/review-queue")
    public ResponseEntity<List<ReviewQueueItem>> reviewQueue() {
        List<ReviewQueueItem> queue = versionRepository
                .findByStatusInOrderBySubmittedAtAsc(
                        List.of(DappVersionStatus.SUBMITTED, DappVersionStatus.IN_REVIEW)).stream()
                .map(version -> {
                    DappListing listing = lifecycleService.requireListing(version.getListingId());
                    return new ReviewQueueItem(
                            version.getId(), listing.getId(), listing.getSlug(), listing.getName(),
                            publisherName(listing), version.getVersion(), version.getStatus(),
                            version.getSubmittedAt());
                })
                .toList();
        return ResponseEntity.ok(queue);
    }

    /** Full review context: manifest, the previously published manifest (for diffing), permissions. */
    @GetMapping("/review/{versionId}")
    public ResponseEntity<ReviewDetailResponse> reviewDetail(@PathVariable UUID versionId) {
        DappVersion version = lifecycleService.requireVersion(versionId);
        DappListing listing = lifecycleService.requireListing(version.getListingId());

        String previousManifest = versionRepository
                .findByListingIdOrderByCreatedAtDesc(listing.getId()).stream()
                .filter(v -> v.getStatus() == DappVersionStatus.PUBLISHED
                        || v.getStatus() == DappVersionStatus.SUPERSEDED)
                .map(DappVersion::getManifestRaw)
                .findFirst()
                .orElse(null);

        List<RequiredPermissionResponse> permissions = requiredPermissionRepository
                .findByVersionId(versionId).stream()
                .map(RequiredPermissionResponse::from)
                .toList();

        return ResponseEntity.ok(new ReviewDetailResponse(
                toListingResponse(listing), VersionResponse.from(version),
                version.getManifestRaw(), previousManifest, permissions,
                paymentMethodResolver.resolve(versionId)));
    }

    @PostMapping("/review/{versionId}/start")
    public ResponseEntity<VersionResponse> startReview(@PathVariable UUID versionId, Authentication auth) {
        DappVersion version = lifecycleService.requireVersion(versionId);
        lifecycleService.startReview(version, SecurityUtils.extractUserId(auth));
        return ResponseEntity.ok(VersionResponse.from(version));
    }

    @PostMapping("/review/{versionId}/approve")
    @RequiresStepUp(requireSecondApprover = true, reason = "dApp marketplace approval")
    public ResponseEntity<VersionResponse> approve(
            @PathVariable UUID versionId,
            @RequestBody(required = false) ReviewNotesRequest request,
            Authentication auth) {
        DappVersion version = lifecycleService.requireVersion(versionId);
        lifecycleService.approve(version, SecurityUtils.extractUserId(auth), primaryRole(auth),
                request != null ? request.notes() : null);
        return ResponseEntity.ok(VersionResponse.from(version));
    }

    @PostMapping("/review/{versionId}/reject")
    @RequiresStepUp(reason = "dApp marketplace rejection")
    public ResponseEntity<VersionResponse> reject(
            @PathVariable UUID versionId,
            @Valid @RequestBody RejectRequest request,
            Authentication auth) {
        DappVersion version = lifecycleService.requireVersion(versionId);
        lifecycleService.reject(version, SecurityUtils.extractUserId(auth), primaryRole(auth), request.reason());
        return ResponseEntity.ok(VersionResponse.from(version));
    }

    // ── Catalog administration ────────────────────────────────────────────────

    @GetMapping("/admin/listings")
    public ResponseEntity<List<ListingResponse>> allListings() {
        List<ListingResponse> listings = listingRepository.findAll().stream()
                .map(this::toListingResponse)
                .toList();
        return ResponseEntity.ok(listings);
    }

    @PostMapping("/listings/{listingId}/deprecate")
    @RequiresStepUp(reason = "dApp deprecation")
    public ResponseEntity<ListingResponse> deprecate(@PathVariable UUID listingId, Authentication auth) {
        DappListing listing = lifecycleService.setLifecycleStatus(
                lifecycleService.requireListing(listingId), DappListingStatus.DEPRECATED,
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(toListingResponse(listing));
    }

    @PostMapping("/listings/{listingId}/delist")
    @RequiresStepUp(reason = "dApp delisting")
    public ResponseEntity<ListingResponse> delist(@PathVariable UUID listingId, Authentication auth) {
        DappListing listing = lifecycleService.setLifecycleStatus(
                lifecycleService.requireListing(listingId), DappListingStatus.DELISTED,
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(toListingResponse(listing));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ListingResponse toListingResponse(DappListing listing) {
        String chainIdentifier = chainConfigRepository.findById(listing.getChainConfigId())
                .map(chain -> chain.getIdentifier())
                .orElse(null);
        return ListingResponse.from(listing, chainIdentifier, publisherName(listing));
    }

    private String publisherName(DappListing listing) {
        return legalEntityRepository.findById(listing.getPublisherEntityId())
                .map(entity -> entity.getCurrentName())
                .orElse(null);
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
    }
}
