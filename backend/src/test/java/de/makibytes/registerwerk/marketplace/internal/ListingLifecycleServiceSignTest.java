package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.*;
import de.makibytes.registerwerk.marketplace.events.DappListingEvent;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for (ecosystem review): {@code sign} previously published no
 * audit event at all for the actual trust-establishing moment (binding an EIP-191 signature to
 * the manifest bytes), and {@code putManifest} silently cleared a prior signature on every
 * re-upload with zero trace.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListingLifecycleService — manifest sign/invalidate audit events")
class ListingLifecycleServiceSignTest {

    @Mock private DappListingRepository listingRepository;
    @Mock private DappVersionRepository versionRepository;
    @Mock private DappRequiredPermissionRepository requiredPermissionRepository;
    @Mock private DappPaymentMethodRepository paymentMethodRepository;
    @Mock private DappReviewEventRepository reviewEventRepository;
    @Mock private de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository permissionDefinitionRepository;
    @Mock private OrgRegistrationRepository orgRegistrationRepository;
    @Mock private PaymentRailRepository paymentRailRepository;
    @Mock private PaymentRailChainAddressRepository paymentRailChainAddressRepository;
    @Mock private ManifestValidationService validationService;
    @Mock private ManifestSigningService signingService;
    @Mock private MarketplaceOnchainAnchorService anchorService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ListingLifecycleService service;

    private final UUID listingId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private DappListing listing;
    private DappVersion version;

    @BeforeEach
    void setUp() {
        service = new ListingLifecycleService(listingRepository, versionRepository, requiredPermissionRepository,
                paymentMethodRepository, reviewEventRepository, permissionDefinitionRepository,
                orgRegistrationRepository, paymentRailRepository, paymentRailChainAddressRepository,
                validationService, signingService, anchorService, eventPublisher);

        listing = new DappListing();
        listing.setId(listingId);
        listing.setPublisherEntityId(entityId);
        listing.setChainConfigId(chainConfigId);
        listing.setSlug("bond-desk");
        listing.setStatus(DappListingStatus.DRAFT);

        version = new DappVersion();
        version.setId(versionId);
        version.setListingId(listingId);
        version.setVersion("1.0.0");
        version.setStatus(DappVersionStatus.DRAFT);
        version.setManifestRaw("{}");
    }

    @Test
    @DisplayName("sign() publishes a MANIFEST_SIGNED event with the signer wallet")
    void sign_publishesManifestSignedEvent() {
        doNothing().when(signingService).verify(anyString(), anyString(), anyString(), any(), any());

        service.sign(listing, version, "0x" + "aa".repeat(65), "0xBEEF000000000000000000000000000000BEEF",
                actorId, "COMPANY_ADMIN");

        ArgumentCaptor<DappListingEvent> captor = ArgumentCaptor.forClass(DappListingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("MANIFEST_SIGNED");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().details()).containsEntry("signerWallet", "0xbeef000000000000000000000000000000beef");
    }

    @Test
    @DisplayName("putManifest() does not publish an invalidation event on a version's first manifest upload")
    void putManifest_noInvalidationEventOnFirstUpload() {
        version.setManifestSignature(null); // never signed yet
        stubValidManifest();

        service.putManifest(listing, version, "{\"version\":\"1.0.0\"}", actorId, "COMPANY_ADMIN");

        verify(eventPublisher, never()).publishEvent(any(DappListingEvent.class));
    }

    @Test
    @DisplayName("putManifest() publishes MANIFEST_SIGNATURE_INVALIDATED when a re-upload clears an existing signature")
    void putManifest_publishesInvalidationEventWhenClearingPriorSignature() {
        version.setManifestSignature("0x" + "aa".repeat(65));
        version.setSignerWallet("0xbeef000000000000000000000000000000beef");
        stubValidManifest();

        service.putManifest(listing, version, "{\"version\":\"1.0.1\"}", actorId, "COMPANY_ADMIN");

        ArgumentCaptor<DappListingEvent> captor = ArgumentCaptor.forClass(DappListingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("MANIFEST_SIGNATURE_INVALIDATED");
        assertThat(version.getManifestSignature()).isNull();
        assertThat(version.getSignerWallet()).isNull();
    }

    private void stubValidManifest() {
        var manifest = new ManifestValidationService.ParsedManifest(
                "bond-desk", "Bond Desk", "1.0.1", "general", null, null, null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var result = new ManifestValidationService.ValidationResult(true, java.util.List.of(), manifest);
        when(validationService.validate(anyString(), eq("bond-desk"), eq(chainConfigId))).thenReturn(result);
        when(signingService.manifestHash(anyString())).thenReturn("0x" + "11".repeat(32));
    }
}
