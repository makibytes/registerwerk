package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailAttestation;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddress;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import de.makibytes.registerwerk.payment.events.PaymentRailEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Payment rail catalog administration")
class PaymentRailAdminServiceTest {

    @Mock
    private PaymentRailRepository railRepository;
    @Mock
    private PaymentRailChainAddressRepository chainAddressRepository;
    @Mock
    private ChainConfigRepository chainConfigRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PaymentRailOnchainVerifier onchainVerifier;

    private PaymentRailAdminService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentRailAdminService(railRepository, chainAddressRepository, chainConfigRepository,
                eventPublisher, onchainVerifier);
        lenient().when(railRepository.save(any(PaymentRail.class)))
                .thenAnswer(invocation -> {
                    PaymentRail rail = invocation.getArgument(0);
                    if (rail.getId() == null) {
                        rail.setId(UUID.randomUUID());
                    }
                    return rail;
                });
    }

    @Test
    @DisplayName("creates a rail and emits a CREATED audit event")
    void createsRail() {
        PaymentRail created = service.create("aueur", "AllUnity Euro", PaymentRailType.STABLECOIN, "EUR",
                6, "desc", "AllUnity GmbH", "LEI123", "BaFin EMI", true,
                "https://example.com/whitepaper.pdf", true, Map.of(), actorId, "REGISTRY_ADMIN", approverId);

        assertThat(created.getCode()).isEqualTo("aueur");
        assertThat(created.isEnabled()).as("new rails start disabled").isFalse();
        assertThat(created.getCreatedBy()).isEqualTo(actorId);
        assertThat(created.getUpdatedBy()).isEqualTo(actorId);

        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_CREATED");
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
    }

    @Test
    @DisplayName("rejects creating a rail with a duplicate code")
    void rejectsDuplicateCode() {
        when(railRepository.existsByCode("aueur")).thenReturn(true);

        assertThatThrownBy(() -> service.create("aueur", "AllUnity Euro", PaymentRailType.STABLECOIN, "EUR",
                6, null, null, null, null, true, null, false, Map.of(), actorId, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("disabling a rail flips the enabled flag and emits a DISABLED audit event")
    void disablesRail() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setEnabled(true);
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.setEnabled(rail.getId(), false, actorId, "REGISTRY_ADMIN", null);

        assertThat(result.isEnabled()).isFalse();
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_DISABLED");
    }

    @Test
    @DisplayName("update leaves the code untouched (immutable after creation)")
    void updateKeepsCodeImmutable() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.update(rail.getId(), "New Name", PaymentRailType.STABLECOIN, "EUR",
                6, "updated", null, null, null, true, null, false, Map.of(), actorId, "REGISTRY_ADMIN", null);

        assertThat(result.getCode()).isEqualTo("aueur");
        assertThat(result.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    @DisplayName("update with no chain-address change omits the address diff and dual-control approver from the audit payload")
    void updateWithNoAddressChange_omitsAddressDiff() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setEnabled(false); // keeps the "EMT flag switched on while enabled" auto-disable out of this scenario
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        service.update(rail.getId(), "New Name", PaymentRailType.STABLECOIN, "EUR",
                6, "updated", null, null, null, true, null, false, Map.of(), actorId, "REGISTRY_ADMIN", null);

        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).doesNotContainKeys("oldChainAddresses", "newChainAddresses");
        assertThat(captor.getValue().dualControlApproverId()).isNull();
    }

    @Test
    @DisplayName("update that changes the chain-address mapping records the old/new diff and the dual-control approver")
    void updateWithAddressChange_recordsDiffAndApprover() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setEnabled(false); // keeps the "EMT flag switched on while enabled" auto-disable out of this scenario
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));
        UUID chainId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        // First lookup (old) returns empty; second lookup (new, post-replace) returns the freshly-set address.
        de.makibytes.registerwerk.payment.api.PaymentRailChainAddress newAddress =
                new de.makibytes.registerwerk.payment.api.PaymentRailChainAddress();
        newAddress.setChainConfigId(chainId);
        newAddress.setTokenAddress("0x" + "aa".repeat(20));
        when(chainAddressRepository.findByPaymentRailId(rail.getId()))
                .thenReturn(java.util.List.of())
                .thenReturn(java.util.List.of(newAddress));
        when(chainConfigRepository.existsById(chainId)).thenReturn(true);

        service.update(rail.getId(), "New Name", PaymentRailType.STABLECOIN, "EUR",
                6, "updated", null, null, null, true, null, false,
                Map.of(chainId, "0x" + "aa".repeat(20)), actorId, "REGISTRY_ADMIN", approverId);

        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).containsKeys("oldChainAddresses", "newChainAddresses");
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
    }

    @Test
    @DisplayName("setMicarVerified(true) records the attestation and its actor")
    void setMicarVerified_recordsAttestation() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.setMicarVerified(rail.getId(), true, actorId, "REGISTRY_ADMIN", approverId);

        assertThat(result.isMicarVerified()).isTrue();
        assertThat(result.getMicarVerifiedAt()).isNotNull();
        assertThat(result.getMicarVerifiedBy()).isEqualTo(actorId);
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_MICAR_VERIFIED");
    }

    @Test
    @DisplayName("setMicarVerified(false) clears the attestation")
    void setMicarVerified_clearsAttestation() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(java.time.Instant.now());
        rail.setMicarVerifiedBy(actorId);
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.setMicarVerified(rail.getId(), false, actorId, "REGISTRY_ADMIN", null);

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.getMicarVerifiedAt()).isNull();
        assertThat(result.getMicarVerifiedBy()).isNull();
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_MICAR_VERIFICATION_CLEARED");
    }

    // ── 5A-11: attestation binding, separation of duties, enable gate ───────────────────

    private static final UUID CHAIN = UUID.randomUUID();
    private static final String TOKEN = "0x" + "aa".repeat(20);
    private static final String OTHER_TOKEN = "0x" + "bb".repeat(20);

    private PaymentRailChainAddress address(String token) {
        PaymentRailChainAddress a = new PaymentRailChainAddress();
        a.setChainConfigId(CHAIN);
        a.setTokenAddress(token);
        return a;
    }

    /** An enabled, attested EMT stablecoin rail whose stored addresses are {@code TOKEN}. */
    private PaymentRail attestedEmtRail() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setRailType(PaymentRailType.STABLECOIN);
        rail.setCurrency("EUR");
        rail.setDecimals(6);
        rail.setIssuerName("AllUnity GmbH");
        rail.setIssuerLei("LEI123");
        rail.setMicarAuthorization("BaFin EMI");
        rail.setEmtFlag(true);
        rail.setWhitePaperUrl("https://example.com/wp.pdf");
        rail.setRedemptionAtPar(true);
        rail.setEnabled(true);
        rail.setCreatedBy(UUID.randomUUID());
        rail.setUpdatedBy(rail.getCreatedBy());
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(java.time.Instant.now());
        rail.setMicarVerifiedBy(actorId);
        rail.setMicarAttestedFingerprint(PaymentRailAttestation.fingerprint(rail, Map.of(CHAIN, TOKEN)));
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));
        return rail;
    }

    private UpdateArgs sameAs(PaymentRail r) {
        return new UpdateArgs(r.getDisplayName(), r.getCurrency(), r.getDecimals(), r.getIssuerName(),
                r.getIssuerLei(), r.getMicarAuthorization(), r.isEmtFlag(), r.getWhitePaperUrl(),
                r.isRedemptionAtPar(), TOKEN);
    }

    private record UpdateArgs(String displayName, String currency, Integer decimals, String issuer, String lei,
                              String auth, boolean emt, String wp, boolean par, String token) {
        UpdateArgs token(String t) { return new UpdateArgs(displayName, currency, decimals, issuer, lei, auth, emt, wp, par, t); }
        UpdateArgs currency(String c) { return new UpdateArgs(displayName, c, decimals, issuer, lei, auth, emt, wp, par, token); }
        UpdateArgs decimals(Integer d) { return new UpdateArgs(displayName, currency, d, issuer, lei, auth, emt, wp, par, token); }
        UpdateArgs issuer(String i) { return new UpdateArgs(displayName, currency, decimals, i, lei, auth, emt, wp, par, token); }
        UpdateArgs lei(String l) { return new UpdateArgs(displayName, currency, decimals, issuer, l, auth, emt, wp, par, token); }
        UpdateArgs displayName(String n) { return new UpdateArgs(n, currency, decimals, issuer, lei, auth, emt, wp, par, token); }
    }

    private PaymentRail runUpdate(PaymentRail rail, UpdateArgs a) {
        when(chainAddressRepository.findByPaymentRailId(rail.getId()))
                .thenReturn(java.util.List.of(address(TOKEN)))   // before
                .thenReturn(java.util.List.of(address(a.token()))); // after replace
        lenient().when(chainConfigRepository.existsById(CHAIN)).thenReturn(true);
        return service.update(rail.getId(), a.displayName(), PaymentRailType.STABLECOIN, a.currency(), a.decimals(),
                "d", a.issuer(), a.lei(), a.auth(), a.emt(), a.wp(), a.par(), Map.of(CHAIN, a.token()),
                actorId, "REGISTRY_ADMIN", approverId);
    }

    @Test
    @DisplayName("swapping the token address voids the attestation and auto-disables the EMT rail with a reason")
    void tokenAddressSwap_voidsAttestation_andDisablesEmtRail() {
        PaymentRail rail = attestedEmtRail();

        PaymentRail result = runUpdate(rail, sameAs(rail).token(OTHER_TOKEN));

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.getMicarAttestedFingerprint()).isNull();
        assertThat(result.getMicarVerifiedAt()).isNull();
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getDisabledReason()).isEqualTo("MICAR_ATTESTATION_INVALIDATED");
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(PaymentRailEvent::eventType)
                .contains("PAYMENT_RAIL_MICAR_ATTESTATION_INVALIDATED", "PAYMENT_RAIL_DISABLED", "PAYMENT_RAIL_UPDATED");
    }

    @Test
    @DisplayName("issuer name, LEI, currency and decimals changes each void the attestation")
    void otherAttestedFactChanges_voidAttestation() {
        for (java.util.function.Function<UpdateArgs, UpdateArgs> change : java.util.List.<java.util.function.Function<UpdateArgs, UpdateArgs>>of(
                a -> a.issuer("Other Issuer AG"), a -> a.lei("LEI999"), a -> a.currency("USD"), a -> a.decimals(18))) {
            PaymentRail rail = attestedEmtRail();
            PaymentRail result = runUpdate(rail, change.apply(sameAs(rail)));
            assertThat(result.isMicarVerified()).isFalse();
            assertThat(result.isEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("a non-attested change (display name) keeps the attestation and the rail enabled")
    void displayNameChange_keepsAttestation() {
        PaymentRail rail = attestedEmtRail();

        PaymentRail result = runUpdate(rail, sameAs(rail).displayName("Renamed"));

        assertThat(result.isMicarVerified()).isTrue();
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getUpdatedBy()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("voiding the attestation of a non-EMT rail does not disable it")
    void voidNonEmtRail_staysEnabled() {
        PaymentRail rail = attestedEmtRail();
        rail.setEmtFlag(false);
        rail.setMicarAttestedFingerprint(PaymentRailAttestation.fingerprint(rail, Map.of(CHAIN, TOKEN)));

        PaymentRail result = runUpdate(rail, sameAs(rail).lei("LEI999"));

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("the creator and the last editor cannot attest; a third operator can, binding the fingerprint")
    void attestation_separationOfDuties() {
        PaymentRail rail = attestedEmtRail();
        rail.setMicarVerified(false);
        rail.setMicarAttestedFingerprint(null);
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        rail.setCreatedBy(creator);
        rail.setUpdatedBy(editor);
        when(chainAddressRepository.findByPaymentRailId(rail.getId())).thenReturn(java.util.List.of(address(TOKEN)));

        assertThatThrownBy(() -> service.setMicarVerified(rail.getId(), true, creator, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.setMicarVerified(rail.getId(), true, editor, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(rail.isMicarVerified()).isFalse();

        PaymentRail result = service.setMicarVerified(rail.getId(), true, actorId, "REGISTRY_ADMIN", approverId);
        assertThat(result.isMicarVerified()).isTrue();
        assertThat(result.getMicarAttestedFingerprint())
                .isEqualTo(PaymentRailAttestation.fingerprint(rail, Map.of(CHAIN, TOKEN)));
    }

    @Test
    @DisplayName("enabling an unattested EMT stablecoin rail is refused")
    void enableUnattestedEmt_isRefused() {
        PaymentRail rail = attestedEmtRail();
        rail.setEnabled(false);
        rail.setMicarVerified(false);
        rail.setMicarAttestedFingerprint(null);
        when(chainAddressRepository.findByPaymentRailId(rail.getId())).thenReturn(java.util.List.of(address(TOKEN)));

        assertThatThrownBy(() -> service.setEnabled(rail.getId(), true, actorId, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MiCAR attestation");
        assertThat(rail.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a legacy attestation flag without a bound fingerprint does not allow enabling an EMT rail")
    void enableWithLegacyFlagOnly_isRefused() {
        PaymentRail rail = attestedEmtRail();
        rail.setEnabled(false);
        rail.setMicarAttestedFingerprint(null); // migrated row: flag set, no fingerprint
        when(chainAddressRepository.findByPaymentRailId(rail.getId())).thenReturn(java.util.List.of(address(TOKEN)));

        assertThatThrownBy(() -> service.setEnabled(rail.getId(), true, actorId, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("enabling an attested EMT rail succeeds, clears the disabled reason and records the approver")
    void enableAttestedEmt_succeeds() {
        PaymentRail rail = attestedEmtRail();
        rail.setEnabled(false);
        rail.setDisabledReason("MICAR_ATTESTATION_INVALIDATED");
        when(chainAddressRepository.findByPaymentRailId(rail.getId())).thenReturn(java.util.List.of(address(TOKEN)));

        PaymentRail result = service.setEnabled(rail.getId(), true, actorId, "REGISTRY_ADMIN", approverId);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getDisabledReason()).isNull();
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
    }

    @Test
    @DisplayName("unverifying an enabled EMT rail clears the attestation and disables the rail")
    void unverifyEnabledEmt_disablesRail() {
        PaymentRail rail = attestedEmtRail();

        PaymentRail result = service.setMicarVerified(rail.getId(), false, actorId, "REGISTRY_ADMIN", null);

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getDisabledReason()).isEqualTo("MICAR_ATTESTATION_INVALIDATED");
    }

    @Test
    @DisplayName("creation and update run the on-chain sanity check; a failing check aborts the save")
    void onchainVerifierRunsAndFailsClosed() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Declared decimals 6 differ"))
                .when(onchainVerifier).verify(any(), any(), any());

        assertThatThrownBy(() -> service.create("aueur", "AllUnity Euro", PaymentRailType.STABLECOIN, "EUR",
                6, null, null, null, null, true, null, false, Map.of(CHAIN, TOKEN), actorId, "REGISTRY_ADMIN", approverId))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimals");
        org.mockito.Mockito.verify(railRepository, org.mockito.Mockito.never()).save(any(PaymentRail.class));
    }
}
