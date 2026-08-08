package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermission;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappReviewEvent;
import de.makibytes.registerwerk.marketplace.api.DappReviewEventRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.marketplace.events.DappListingEvent;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionStatus;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The marketplace review state machine:
 * DRAFT → SUBMITTED → IN_REVIEW → APPROVED | REJECTED; approval anchors the manifest
 * hash in the onchain DappRegistry, and the receipt poller flips the version (and
 * listing) to PUBLISHED. Deprecate/delist mirror onchain setStatus.
 */
@Service
@Transactional
public class ListingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ListingLifecycleService.class);

    private final DappListingRepository listingRepository;
    private final DappVersionRepository versionRepository;
    private final DappRequiredPermissionRepository requiredPermissionRepository;
    private final DappPaymentMethodRepository paymentMethodRepository;
    private final DappReviewEventRepository reviewEventRepository;
    private final PermissionDefinitionRepository permissionDefinitionRepository;
    private final OrgRegistrationRepository orgRegistrationRepository;
    private final PaymentRailRepository paymentRailRepository;
    private final PaymentRailChainAddressRepository paymentRailChainAddressRepository;
    private final ManifestValidationService validationService;
    private final ManifestSigningService signingService;
    private final MarketplaceOnchainAnchorService anchorService;
    private final ApplicationEventPublisher eventPublisher;

    ListingLifecycleService(
            DappListingRepository listingRepository,
            DappVersionRepository versionRepository,
            DappRequiredPermissionRepository requiredPermissionRepository,
            DappPaymentMethodRepository paymentMethodRepository,
            DappReviewEventRepository reviewEventRepository,
            PermissionDefinitionRepository permissionDefinitionRepository,
            OrgRegistrationRepository orgRegistrationRepository,
            PaymentRailRepository paymentRailRepository,
            PaymentRailChainAddressRepository paymentRailChainAddressRepository,
            ManifestValidationService validationService,
            ManifestSigningService signingService,
            MarketplaceOnchainAnchorService anchorService,
            ApplicationEventPublisher eventPublisher) {
        this.listingRepository = listingRepository;
        this.versionRepository = versionRepository;
        this.requiredPermissionRepository = requiredPermissionRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.reviewEventRepository = reviewEventRepository;
        this.permissionDefinitionRepository = permissionDefinitionRepository;
        this.orgRegistrationRepository = orgRegistrationRepository;
        this.paymentRailRepository = paymentRailRepository;
        this.paymentRailChainAddressRepository = paymentRailChainAddressRepository;
        this.validationService = validationService;
        this.signingService = signingService;
        this.anchorService = anchorService;
        this.eventPublisher = eventPublisher;
    }

    // ── Publisher: create / manifest / sign / submit ─────────────────────────

    public DappListing createListing(UUID publisherEntityId, UUID chainConfigId,
                                     String slug, String name, String category) {
        String normalizedSlug = slug.trim().toLowerCase();
        if (listingRepository.existsBySlug(normalizedSlug)) {
            throw new InvalidStateTransitionException("Slug '" + normalizedSlug + "' is already taken");
        }
        requireActiveOrg(publisherEntityId, chainConfigId);

        DappListing listing = new DappListing();
        listing.setPublisherEntityId(publisherEntityId);
        listing.setChainConfigId(chainConfigId);
        listing.setSlug(normalizedSlug);
        listing.setDappIdHash(DappRegistryAbi.hashHex(normalizedSlug));
        listing.setName(name);
        listing.setCategory(category != null && !category.isBlank() ? category : "general");
        listing.setStatus(DappListingStatus.DRAFT);
        DappListing saved = listingRepository.save(listing);

        DappVersion version = new DappVersion();
        version.setListingId(saved.getId());
        version.setVersion("0.0.0");
        versionRepository.save(version);
        return saved;
    }

    /** Creates a new DRAFT version for a published listing (version updates re-enter review). */
    public DappVersion createVersion(DappListing listing) {
        boolean hasOpenVersion = versionRepository.findByListingIdOrderByCreatedAtDesc(listing.getId()).stream()
                .anyMatch(v -> v.getStatus() == DappVersionStatus.DRAFT
                        || v.getStatus() == DappVersionStatus.SUBMITTED
                        || v.getStatus() == DappVersionStatus.IN_REVIEW
                        || v.getStatus() == DappVersionStatus.APPROVED);
        if (hasOpenVersion) {
            throw new InvalidStateTransitionException("An open (draft or in-review) version already exists");
        }
        DappVersion version = new DappVersion();
        version.setListingId(listing.getId());
        version.setVersion("0.0.0");
        return versionRepository.save(version);
    }

    /**
     * Stores and validates the manifest for a draft version. The raw bytes are kept
     * verbatim (hash/signature input); metadata is synced from the parsed manifest.
     */
    public ManifestValidationService.ValidationResult putManifest(DappListing listing, DappVersion version,
                                                                  String manifestRaw, UUID actorId, String actorRole) {
        requireVersionStatus(version, DappVersionStatus.DRAFT);
        if (manifestRaw == null || manifestRaw.isBlank()) {
            throw new IllegalArgumentException("Manifest content is required");
        }
        if (manifestRaw.length() > 1_000_000) {
            throw new IllegalArgumentException("Manifest exceeds the 1,000,000 character limit");
        }

        var result = validationService.validate(manifestRaw, listing.getSlug(), listing.getChainConfigId());
        if (!result.valid()) {
            return result;
        }
        var manifest = result.manifest();

        // A prior signature is being invalidated by this re-upload — the only trace of that
        // used to be an overwritten column with nothing recording it happened.
        boolean invalidatingPriorSignature = version.getManifestSignature() != null;

        version.setManifestRaw(manifestRaw);
        version.setManifestJson(manifestRaw);
        version.setManifestHash(signingService.manifestHash(manifestRaw));
        version.setVersion(manifest.version());
        version.setManifestSignature(null);
        version.setSignerWallet(null);
        versionRepository.save(version);

        if (invalidatingPriorSignature) {
            eventPublisher.publishEvent(new DappListingEvent("MANIFEST_SIGNATURE_INVALIDATED", listing.getId(),
                    actorId, actorRole, Map.of("slug", listing.getSlug(), "versionId", version.getId().toString())));
        }

        requiredPermissionRepository.deleteByVersionId(version.getId());
        for (var permission : manifest.requiredPermissions()) {
            DappRequiredPermission required = new DappRequiredPermission();
            required.setVersionId(version.getId());
            required.setPermissionCode(permission.code());
            required.setPermissionHash(DappRegistryAbi.hashHex(permission.code()));
            required.setClaimTopics(manifest.requiredClaimTopics());
            required.setRationale(permission.rationale());
            requiredPermissionRepository.save(required);
            autoRegisterDraftDefinition(listing, permission.code(), permission.rationale());
        }

        paymentMethodRepository.deleteByVersionId(version.getId());
        for (var method : manifest.paymentMethods()) {
            DappPaymentMethod paymentMethod = new DappPaymentMethod();
            paymentMethod.setVersionId(version.getId());
            paymentMethod.setMethodType("RAIL".equals(method.methodType())
                    ? DappPaymentMethod.MethodType.RAIL : DappPaymentMethod.MethodType.CUSTOM);
            paymentMethod.setRailCode(method.railCode());
            paymentMethod.setCustomName(method.customName());
            paymentMethod.setCustomDescription(method.customDescription());
            paymentMethod.setCurrency(method.currency());
            paymentMethod.setNote(method.note());
            paymentMethodRepository.save(paymentMethod);
        }

        listing.setName(manifest.name());
        listing.setCategory(manifest.category());
        listing.setDocsUrl(manifest.docsUrl());
        listing.setContactEmail(manifest.contact());
        listing.setPricingNote(manifest.pricingNote());
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
        return result;
    }

    /** Attaches the publisher's EIP-191 signature over keccak256(manifest_raw). */
    public void sign(DappListing listing, DappVersion version, String signature, String signerWallet,
                     UUID actorId, String actorRole) {
        requireVersionStatus(version, DappVersionStatus.DRAFT);
        if (version.getManifestRaw() == null) {
            throw new InvalidStateTransitionException("Upload a valid manifest before signing");
        }
        signingService.verify(version.getManifestRaw(), signature, signerWallet,
                listing.getPublisherEntityId(), listing.getChainConfigId());
        version.setManifestSignature(signature);
        version.setSignerWallet(signerWallet.toLowerCase());
        versionRepository.save(version);

        // The actual trust-establishing moment: this signature is what MarketplaceOnchainAnchorService
        // eventually anchors against, so it must be visible in the audit chain.
        eventPublisher.publishEvent(new DappListingEvent("MANIFEST_SIGNED", listing.getId(), actorId, actorRole,
                Map.of("slug", listing.getSlug(), "versionId", version.getId().toString(),
                        "signerWallet", version.getSignerWallet())));
    }

    public void submit(DappListing listing, DappVersion version, UUID actorId, String actorRole) {
        requireVersionStatus(version, DappVersionStatus.DRAFT);
        if (version.getManifestRaw() == null || version.getManifestSignature() == null) {
            throw new InvalidStateTransitionException("A validated, signed manifest is required before submission");
        }

        version.setStatus(DappVersionStatus.SUBMITTED);
        version.setSubmittedAt(Instant.now());
        versionRepository.save(version);
        if (listing.getStatus() == DappListingStatus.DRAFT || listing.getStatus() == DappListingStatus.REJECTED) {
            listing.setStatus(DappListingStatus.SUBMITTED);
            listingRepository.save(listing);
        }
        recordReview(version, "SUBMITTED", actorId, null);
        eventPublisher.publishEvent(new DappListingEvent("SUBMITTED", listing.getId(), actorId, actorRole,
                Map.of("slug", listing.getSlug(), "version", version.getVersion())));
    }

    // ── Operator: review ──────────────────────────────────────────────────────

    public void startReview(DappVersion version, UUID actorId) {
        requireVersionStatus(version, DappVersionStatus.SUBMITTED);
        version.setStatus(DappVersionStatus.IN_REVIEW);
        versionRepository.save(version);

        DappListing listing = requireListing(version.getListingId());
        if (listing.getStatus() == DappListingStatus.SUBMITTED) {
            listing.setStatus(DappListingStatus.IN_REVIEW);
            listingRepository.save(listing);
        }
        recordReview(version, "REVIEW_STARTED", actorId, null);
    }

    /**
     * Approves the version and schedules its onchain anchor: the first approved version
     * registers the dApp, later ones update the manifest anchor. The chain transaction
     * is broadcast only after this method's transaction commits (see {@link AfterCommit}
     * / {@link MarketplaceOnchainAnchorService}) so an irreversible tx is never sent for
     * an approval that ends up rolled back; PUBLISHED follows on receipt confirmation
     * via {@link MarketplaceTxPoller}.
     *
     * <p>Re-verifies the manifest signature and signer-wallet binding — both were only
     * checked at {@code sign} time, and the signer's wallet may have been unbound (or
     * the org suspended) since submission — and that every referenced payment rail is
     * still enabled, since a rail can be disabled by the operator after submission.
     */
    public void approve(DappVersion version, UUID actorId, String actorRole, String notes) {
        if (version.getStatus() != DappVersionStatus.SUBMITTED
                && version.getStatus() != DappVersionStatus.IN_REVIEW) {
            throw new InvalidStateTransitionException(
                    "Version is " + version.getStatus() + "; only SUBMITTED or IN_REVIEW versions can be approved");
        }
        DappListing listing = requireListing(version.getListingId());
        requireActiveOrg(listing.getPublisherEntityId(), listing.getChainConfigId());

        if (version.getManifestSignature() == null || version.getSignerWallet() == null) {
            throw new InvalidStateTransitionException("Version has no manifest signature");
        }
        signingService.verify(version.getManifestRaw(), version.getManifestSignature(), version.getSignerWallet(),
                listing.getPublisherEntityId(), listing.getChainConfigId());

        for (DappPaymentMethod method : paymentMethodRepository.findByVersionId(version.getId())) {
            if (method.getMethodType() != DappPaymentMethod.MethodType.RAIL) continue;
            var rail = paymentRailRepository.findByCode(method.getRailCode()).filter(PaymentRail::isEnabled);
            if (rail.isEmpty()) {
                throw new InvalidStateTransitionException(
                        "Payment rail '" + method.getRailCode() + "' was disabled after submission; "
                        + "the publisher must update the manifest before this version can be approved");
            }
            if (rail.get().getRailType().isChainBound()
                    && !paymentRailChainAddressRepository
                            .existsByPaymentRailIdAndChainConfigId(rail.get().getId(), listing.getChainConfigId())) {
                throw new InvalidStateTransitionException(
                        "Payment rail '" + method.getRailCode()
                        + "' no longer has a deployed contract address on this dApp's anchor chain; "
                        + "the publisher must update the manifest before this version can be approved");
            }
        }

        version.setStatus(DappVersionStatus.APPROVED);
        version.setReviewedBy(actorId);
        version.setReviewedAt(Instant.now());
        version.setReviewNotes(notes);
        versionRepository.save(version);
        AfterCommit.run(() -> anchorService.anchorApproval(version.getId()));

        listing.setStatus(DappListingStatus.APPROVED);
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);

        activateDraftDefinitions(listing);
        recordReview(version, "APPROVED", actorId, notes);
        eventPublisher.publishEvent(new DappListingEvent("APPROVED", listing.getId(), actorId, actorRole,
                Map.of("slug", listing.getSlug(), "version", version.getVersion())));
    }

    public void reject(DappVersion version, UUID actorId, String actorRole, String reason) {
        if (version.getStatus() != DappVersionStatus.SUBMITTED
                && version.getStatus() != DappVersionStatus.IN_REVIEW) {
            throw new InvalidStateTransitionException(
                    "Version is " + version.getStatus() + "; only SUBMITTED or IN_REVIEW versions can be rejected");
        }
        version.setStatus(DappVersionStatus.REJECTED);
        version.setReviewedBy(actorId);
        version.setReviewedAt(Instant.now());
        version.setReviewNotes(reason);
        versionRepository.save(version);

        DappListing listing = requireListing(version.getListingId());
        if (listing.getStatus() != DappListingStatus.PUBLISHED) {
            listing.setStatus(DappListingStatus.REJECTED);
            listingRepository.save(listing);
        }
        recordReview(version, "REJECTED", actorId, reason);
        eventPublisher.publishEvent(new DappListingEvent("REJECTED", listing.getId(), actorId, actorRole,
                Map.of("slug", listing.getSlug(), "version", version.getVersion(), "reason", reason)));
    }

    /** Deprecates or delists a published dApp, mirroring onchain setStatus. */
    public DappListing setLifecycleStatus(DappListing listing, DappListingStatus target,
                                          UUID actorId, String actorRole) {
        if (target != DappListingStatus.DEPRECATED && target != DappListingStatus.DELISTED) {
            throw new IllegalArgumentException("Only DEPRECATED or DELISTED are valid targets");
        }
        if (listing.getStatus() != DappListingStatus.PUBLISHED
                && listing.getStatus() != DappListingStatus.DEPRECATED) {
            throw new InvalidStateTransitionException(
                    "Listing is " + listing.getStatus() + "; only published listings can be deprecated or delisted");
        }

        listing.setStatus(target);
        listing.setUpdatedAt(Instant.now());
        DappListing saved = listingRepository.save(listing);
        AfterCommit.run(() -> anchorService.broadcastLifecycleStatus(saved.getId()));
        eventPublisher.publishEvent(new DappListingEvent(target.name(), listing.getId(), actorId, actorRole,
                Map.of("slug", listing.getSlug())));
        return saved;
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public DappListing requireListing(UUID listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("DappListing", listingId));
    }

    public DappVersion requireVersion(UUID versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("DappVersion", versionId));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Auto-registers namespaced permissions as DRAFT definitions; ACTIVE on approval. */
    private void autoRegisterDraftDefinition(DappListing listing, String code, String rationale) {
        if (!code.startsWith(listing.getSlug() + ".")) return;
        if (permissionDefinitionRepository.existsByCode(code)) return;

        PermissionDefinition definition = new PermissionDefinition();
        definition.setCode(code);
        definition.setPermissionHash(DappRegistryAbi.hashHex(code));
        definition.setName(code);
        definition.setDescription(rationale);
        definition.setDappListingId(listing.getId());
        definition.setStatus(PermissionDefinitionStatus.DRAFT);
        permissionDefinitionRepository.save(definition);
        log.info("Auto-registered draft permission '{}' for dApp {}", code, listing.getSlug());
    }

    private void activateDraftDefinitions(DappListing listing) {
        permissionDefinitionRepository
                .findByDappListingIdAndStatus(listing.getId(), PermissionDefinitionStatus.DRAFT)
                .forEach(def -> {
                    def.setStatus(PermissionDefinitionStatus.ACTIVE);
                    permissionDefinitionRepository.save(def);
                });
    }

    private OrgRegistration requireActiveOrg(UUID entityId, UUID chainConfigId) {
        OrgRegistration org = orgRegistrationRepository
                .findByLegalEntityIdAndChainConfigId(entityId, chainConfigId)
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "The publisher has no organization registration on the selected chain — "
                        + "onchain org identity is a prerequisite for publishing"));
        if (org.getStatus() != OrgRegistrationStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "The publisher's organization registration is " + org.getStatus() + ", not ACTIVE");
        }
        return org;
    }

    private void requireVersionStatus(DappVersion version, DappVersionStatus expected) {
        if (version.getStatus() != expected) {
            throw new InvalidStateTransitionException(
                    "Version is " + version.getStatus() + ", expected " + expected);
        }
    }

    private void recordReview(DappVersion version, String action, UUID actorId, String notes) {
        DappReviewEvent event = new DappReviewEvent();
        event.setVersionId(version.getId());
        event.setAction(action);
        event.setActorId(actorId);
        event.setNotes(notes);
        reviewEventRepository.save(event);
    }
}
