package de.makibytes.registerwerk.marketplace.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.internal.PaymentMethodResolver;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.CatalogCard;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.CatalogDetail;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.ListingResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.RequiredPermissionResponse;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.VersionResponse;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.api.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.UUID;

/**
 * Public catalog of published dApps. No {@code @PreAuthorize} on purpose: any
 * authenticated user may browse — the global security config (`/api/v1/**` requires a
 * valid JWT, `SecurityConfig`) already keeps this behind authentication, so listings are
 * not exposed anonymously; this controller just adds no further role restriction on top.
 * The detail response carries everything needed for independent integrity verification:
 * the raw manifest, its signature and signer, and the onchain anchor transaction —
 * keccak256(manifestRaw) must equal the DappRegistry's manifestHash.
 */
@RestController
@RequestMapping("/api/v1/marketplace/catalog")
@Validated
public class MarketplaceCatalogController {

    private final DappListingRepository listingRepository;
    private final DappVersionRepository versionRepository;
    private final DappRequiredPermissionRepository requiredPermissionRepository;
    private final PaymentMethodResolver paymentMethodResolver;
    private final LegalEntityRepository legalEntityRepository;
    private final ChainConfigRepository chainConfigRepository;

    public MarketplaceCatalogController(
            DappListingRepository listingRepository,
            DappVersionRepository versionRepository,
            DappRequiredPermissionRepository requiredPermissionRepository,
            PaymentMethodResolver paymentMethodResolver,
            LegalEntityRepository legalEntityRepository,
            ChainConfigRepository chainConfigRepository) {
        this.listingRepository = listingRepository;
        this.versionRepository = versionRepository;
        this.requiredPermissionRepository = requiredPermissionRepository;
        this.paymentMethodResolver = paymentMethodResolver;
        this.legalEntityRepository = legalEntityRepository;
        this.chainConfigRepository = chainConfigRepository;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CatalogCard>> browse(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        var result = listingRepository.searchCatalog(
                emptyToNull(query), emptyToNull(category), PageRequest.of(page, size));
        return ResponseEntity.ok(PageResponse.of(result.map(this::toCard)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<CatalogDetail> detail(@PathVariable String slug) {
        DappListing listing = listingRepository.findBySlug(slug.toLowerCase())
                .filter(l -> l.getCurrentVersionId() != null)
                .orElseThrow(() -> new EntityNotFoundException("DappListing", (UUID) null));

        DappVersion version = versionRepository.findById(listing.getCurrentVersionId())
                .orElseThrow(() -> new EntityNotFoundException("DappVersion", listing.getCurrentVersionId()));

        List<RequiredPermissionResponse> permissions = requiredPermissionRepository
                .findByVersionId(version.getId()).stream()
                .map(RequiredPermissionResponse::from)
                .toList();

        String chainIdentifier = chainConfigRepository.findById(listing.getChainConfigId())
                .map(chain -> chain.getIdentifier())
                .orElse(null);
        String publisherName = legalEntityRepository.findById(listing.getPublisherEntityId())
                .map(entity -> entity.getCurrentName())
                .orElse(null);

        return ResponseEntity.ok(new CatalogDetail(
                ListingResponse.from(listing, chainIdentifier, publisherName),
                VersionResponse.from(version),
                version.getManifestRaw(),
                version.getManifestSignature(),
                permissions,
                paymentMethodResolver.resolve(version.getId())));
    }

    /** Distinct categories among published listings, for catalog filter options. */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> categories() {
        return ResponseEntity.ok(listingRepository.findDistinctPublishedCategories());
    }

    private CatalogCard toCard(DappListing listing) {
        String publisherName = legalEntityRepository.findById(listing.getPublisherEntityId())
                .map(entity -> entity.getCurrentName())
                .orElse(null);
        String version = null;
        int permissionCount = 0;
        List<MarketplaceDtos.PaymentMethodResponse> paymentMethods = List.of();
        if (listing.getCurrentVersionId() != null) {
            version = versionRepository.findById(listing.getCurrentVersionId())
                    .map(DappVersion::getVersion)
                    .orElse(null);
            permissionCount = requiredPermissionRepository.findByVersionId(listing.getCurrentVersionId()).size();
            paymentMethods = paymentMethodResolver.resolve(listing.getCurrentVersionId());
        }
        return new CatalogCard(listing.getSlug(), listing.getName(), listing.getCategory(),
                publisherName, version, permissionCount, listing.getPricingNote(), paymentMethods,
                listing.getUpdatedAt());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
