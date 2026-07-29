package de.makibytes.registerwerk.marketplace.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.internal.ListingLifecycleService;
import de.makibytes.registerwerk.marketplace.internal.ManifestSigningService;
import de.makibytes.registerwerk.marketplace.internal.PaymentMethodResolver;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.CreateListingRequest;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ListingResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ManifestValidationResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.PaymentMethodResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.RequiredPermissionResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.SignManifestRequest;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.VersionResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Publisher console: create listings, upload/validate/sign manifests and submit them
 * for operator review. The publisher entity always comes from the JWT.
 */
@RestController
@RequestMapping("/api/v1/marketplace")
@PreAuthorize("hasAnyRole('DAPP_PUBLISHER','COMPANY_ADMIN')")
public class MarketplacePublisherController {

    private final ListingLifecycleService lifecycleService;
    private final ManifestSigningService signingService;
    private final DappListingRepository listingRepository;
    private final DappVersionRepository versionRepository;
    private final DappRequiredPermissionRepository requiredPermissionRepository;
    private final PaymentMethodResolver paymentMethodResolver;
    private final ChainConfigRepository chainConfigRepository;

    public MarketplacePublisherController(
            ListingLifecycleService lifecycleService,
            ManifestSigningService signingService,
            DappListingRepository listingRepository,
            DappVersionRepository versionRepository,
            DappRequiredPermissionRepository requiredPermissionRepository,
            PaymentMethodResolver paymentMethodResolver,
            ChainConfigRepository chainConfigRepository) {
        this.lifecycleService = lifecycleService;
        this.signingService = signingService;
        this.listingRepository = listingRepository;
        this.versionRepository = versionRepository;
        this.requiredPermissionRepository = requiredPermissionRepository;
        this.paymentMethodResolver = paymentMethodResolver;
        this.chainConfigRepository = chainConfigRepository;
    }

    @PostMapping("/listings")
    public ResponseEntity<ListingResponse> createListing(
            @Valid @RequestBody CreateListingRequest request, Authentication auth) {
        DappListing listing = lifecycleService.createListing(
                requireEntityId(auth), request.chainConfigId(),
                request.slug(), request.name(), request.category());
        return ResponseEntity.status(HttpStatus.CREATED).body(toListingResponse(listing));
    }

    @GetMapping("/my-listings")
    public ResponseEntity<List<ListingResponse>> myListings(Authentication auth) {
        List<ListingResponse> listings = listingRepository
                .findByPublisherEntityIdOrderByCreatedAtDesc(requireEntityId(auth)).stream()
                .map(this::toListingResponse)
                .toList();
        return ResponseEntity.ok(listings);
    }

    @GetMapping("/listings/{listingId}")
    public ResponseEntity<ListingResponse> getListing(@PathVariable UUID listingId, Authentication auth) {
        return ResponseEntity.ok(toListingResponse(requireOwnListing(listingId, auth)));
    }

    @GetMapping("/listings/{listingId}/versions")
    public ResponseEntity<List<VersionResponse>> listVersions(@PathVariable UUID listingId, Authentication auth) {
        requireOwnListing(listingId, auth);
        List<VersionResponse> versions = versionRepository
                .findByListingIdOrderByCreatedAtDesc(listingId).stream()
                .map(VersionResponse::from)
                .toList();
        return ResponseEntity.ok(versions);
    }

    /** Starts a new draft version for an already-published listing. */
    @PostMapping("/listings/{listingId}/versions")
    public ResponseEntity<VersionResponse> createVersion(@PathVariable UUID listingId, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        DappVersion version = lifecycleService.createVersion(listing);
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionResponse.from(version));
    }

    /** Stores and validates the manifest (raw body = the exact bytes to be signed). */
    @PutMapping(value = "/listings/{listingId}/versions/{versionId}/manifest", consumes = "application/json")
    public ResponseEntity<ManifestValidationResponse> putManifest(
            @PathVariable UUID listingId, @PathVariable UUID versionId,
            @RequestBody String manifestRaw, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        DappVersion version = requireVersionOf(listing, versionId);

        var result = lifecycleService.putManifest(listing, version, manifestRaw,
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(new ManifestValidationResponse(
                result.valid(), result.errors(),
                result.valid() ? signingService.manifestHash(manifestRaw) : null));
    }

    @GetMapping("/listings/{listingId}/versions/{versionId}/permissions")
    public ResponseEntity<List<RequiredPermissionResponse>> versionPermissions(
            @PathVariable UUID listingId, @PathVariable UUID versionId, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        requireVersionOf(listing, versionId);
        List<RequiredPermissionResponse> permissions = requiredPermissionRepository
                .findByVersionId(versionId).stream()
                .map(RequiredPermissionResponse::from)
                .toList();
        return ResponseEntity.ok(permissions);
    }

    @GetMapping("/listings/{listingId}/versions/{versionId}/payment-methods")
    public ResponseEntity<List<PaymentMethodResponse>> versionPaymentMethods(
            @PathVariable UUID listingId, @PathVariable UUID versionId, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        requireVersionOf(listing, versionId);
        return ResponseEntity.ok(paymentMethodResolver.resolve(versionId));
    }

    @PostMapping("/listings/{listingId}/versions/{versionId}/sign")
    public ResponseEntity<VersionResponse> sign(
            @PathVariable UUID listingId, @PathVariable UUID versionId,
            @Valid @RequestBody SignManifestRequest request, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        DappVersion version = requireVersionOf(listing, versionId);
        lifecycleService.sign(listing, version, request.signature(), request.signerWallet(),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(VersionResponse.from(version));
    }

    @PostMapping("/listings/{listingId}/versions/{versionId}/submit")
    public ResponseEntity<VersionResponse> submit(
            @PathVariable UUID listingId, @PathVariable UUID versionId, Authentication auth) {
        DappListing listing = requireOwnListing(listingId, auth);
        DappVersion version = requireVersionOf(listing, versionId);
        lifecycleService.submit(listing, version, SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.ok(VersionResponse.from(version));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DappListing requireOwnListing(UUID listingId, Authentication auth) {
        DappListing listing = lifecycleService.requireListing(listingId);
        if (!listing.getPublisherEntityId().equals(requireEntityId(auth))) {
            throw new EntityNotFoundException("DappListing", listingId);
        }
        return listing;
    }

    private DappVersion requireVersionOf(DappListing listing, UUID versionId) {
        DappVersion version = lifecycleService.requireVersion(versionId);
        if (!version.getListingId().equals(listing.getId())) {
            throw new EntityNotFoundException("DappVersion", versionId);
        }
        return version;
    }

    private ListingResponse toListingResponse(DappListing listing) {
        String chainIdentifier = chainConfigRepository.findById(listing.getChainConfigId())
                .map(chain -> chain.getIdentifier())
                .orElse(null);
        return ListingResponse.from(listing, chainIdentifier, null);
    }

    private static UUID requireEntityId(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new AccessDeniedException("No entity_id claim in token");
        }
        return entityId;
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("DAPP_PUBLISHER");
    }
}
