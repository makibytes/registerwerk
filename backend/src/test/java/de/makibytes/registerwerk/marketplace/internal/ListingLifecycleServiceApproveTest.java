package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.*;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the review-time re-verification fixed alongside the payment
 * rail feature (B5): {@code approve} must not trust the signature/wallet-binding check
 * that was only performed at {@code sign} time — the signer's wallet may have been
 * unbound, or a referenced payment rail disabled, in the time between submission and
 * approval. Chain broadcasting is exercised separately (it now runs post-commit via
 * {@link de.makibytes.registerwerk.shared.AfterCommit}, which is a no-op outside an
 * active transaction, so these unit tests only assert the pre-commit guard behavior).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListingLifecycleService.approve re-verification")
class ListingLifecycleServiceApproveTest {

    @Mock private DappListingRepository listingRepository;
    @Mock private DappVersionRepository versionRepository;
    @Mock private DappRequiredPermissionRepository requiredPermissionRepository;
    @Mock private DappPaymentMethodRepository paymentMethodRepository;
    @Mock private DappReviewEventRepository reviewEventRepository;
    @Mock private PermissionDefinitionRepository permissionDefinitionRepository;
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
        listing.setStatus(DappListingStatus.SUBMITTED);

        version = new DappVersion();
        version.setId(versionId);
        version.setListingId(listingId);
        version.setVersion("1.0.0");
        version.setStatus(DappVersionStatus.SUBMITTED);
        version.setManifestRaw("{}");
        version.setManifestHash("0x" + "11".repeat(32));
        version.setManifestSignature("0x" + "22".repeat(65));
        version.setSignerWallet("0x" + "33".repeat(20));

        OrgRegistration org = new OrgRegistration();
        org.setId(UUID.randomUUID());
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        org.setOrgAddress("0x" + "44".repeat(20));

        lenient().when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        lenient().when(versionRepository.findByListingIdOrderByCreatedAtDesc(listingId)).thenReturn(List.of());
        lenient().when(orgRegistrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainConfigId))
                .thenReturn(Optional.of(org));
        lenient().when(paymentMethodRepository.findByVersionId(versionId)).thenReturn(List.of());
        lenient().when(requiredPermissionRepository.findByVersionId(versionId)).thenReturn(List.of());
        lenient().when(permissionDefinitionRepository.findByDappListingIdAndStatus(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("rejects approval when the signature no longer verifies (signer wallet unbound)")
    void rejectsApprovalWhenSignatureNoLongerVerifies() {
        doThrow(new IllegalArgumentException("Wallet is no longer bound to the publisher entity"))
                .when(signingService).verify(anyString(), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> service.approve(version, UUID.randomUUID(), "REGISTRY_ADMIN", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(anchorService, never()).anchorApproval(any());
        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.SUBMITTED);
    }

    @Test
    @DisplayName("rejects approval when a referenced payment rail was disabled after submission")
    void rejectsApprovalWhenPaymentRailDisabled() {
        DappPaymentMethod method = new DappPaymentMethod();
        method.setVersionId(versionId);
        method.setMethodType(DappPaymentMethod.MethodType.RAIL);
        method.setRailCode("aueur");
        when(paymentMethodRepository.findByVersionId(versionId)).thenReturn(List.of(method));
        when(paymentRailRepository.findByCode("aueur")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(version, UUID.randomUUID(), "REGISTRY_ADMIN", null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("aueur");

        verify(anchorService, never()).anchorApproval(any());
    }

    @Test
    @DisplayName("rejects approval when a chain-bound rail lost its deployed address on this chain")
    void rejectsApprovalWhenChainAddressRemoved() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setRailType(PaymentRailType.STABLECOIN);
        rail.setEnabled(true);

        DappPaymentMethod method = new DappPaymentMethod();
        method.setVersionId(versionId);
        method.setMethodType(DappPaymentMethod.MethodType.RAIL);
        method.setRailCode("aueur");
        when(paymentMethodRepository.findByVersionId(versionId)).thenReturn(List.of(method));
        when(paymentRailRepository.findByCode("aueur")).thenReturn(Optional.of(rail));
        when(paymentRailChainAddressRepository.existsByPaymentRailIdAndChainConfigId(rail.getId(), chainConfigId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.approve(version, UUID.randomUUID(), "REGISTRY_ADMIN", null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("no longer has a deployed contract address");

        verify(anchorService, never()).anchorApproval(any());
    }

    @Test
    @DisplayName("approves when the signature still verifies and all rails remain enabled and deployed")
    void approvesWhenSignatureValidAndRailsEnabled() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setRailType(PaymentRailType.STABLECOIN);
        rail.setEnabled(true);

        DappPaymentMethod method = new DappPaymentMethod();
        method.setVersionId(versionId);
        method.setMethodType(DappPaymentMethod.MethodType.RAIL);
        method.setRailCode("aueur");
        when(paymentMethodRepository.findByVersionId(versionId)).thenReturn(List.of(method));
        when(paymentRailRepository.findByCode("aueur")).thenReturn(Optional.of(rail));
        when(paymentRailChainAddressRepository.existsByPaymentRailIdAndChainConfigId(rail.getId(), chainConfigId))
                .thenReturn(true);

        service.approve(version, UUID.randomUUID(), "REGISTRY_ADMIN", "looks good");

        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.APPROVED);
        assertThat(listing.getStatus()).isEqualTo(DappListingStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.marketplace.events.DappListingEvent.class));
    }
}
