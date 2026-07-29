package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
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

    private PaymentRailAdminService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentRailAdminService(railRepository, chainAddressRepository, chainConfigRepository,
                eventPublisher);
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
                "https://example.com/whitepaper.pdf", true, Map.of(), actorId, "REGISTRY_ADMIN");

        assertThat(created.getCode()).isEqualTo("aueur");
        assertThat(created.isEnabled()).isTrue();

        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_CREATED");
    }

    @Test
    @DisplayName("rejects creating a rail with a duplicate code")
    void rejectsDuplicateCode() {
        when(railRepository.existsByCode("aueur")).thenReturn(true);

        assertThatThrownBy(() -> service.create("aueur", "AllUnity Euro", PaymentRailType.STABLECOIN, "EUR",
                6, null, null, null, null, true, null, false, Map.of(), actorId, "REGISTRY_ADMIN"))
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

        PaymentRail result = service.setEnabled(rail.getId(), false, actorId, "REGISTRY_ADMIN");

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
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        service.update(rail.getId(), "New Name", PaymentRailType.STABLECOIN, "EUR",
                6, "updated", null, null, null, true, null, false, Map.of(), actorId, "REGISTRY_ADMIN", null);

        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).doesNotContainKeys("oldChainAddresses", "newChainAddresses");
        assertThat(captor.getValue().dualControlApproverId()).isNull();
    }

    @Test
    @DisplayName("update that changes the chain-address mapping records the old/new diff and the dual-control approver (finding #1)")
    void updateWithAddressChange_recordsDiffAndApprover() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
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
    @DisplayName("setMicarVerified(true) records the attestation and its actor (finding #8)")
    void setMicarVerified_recordsAttestation() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.setMicarVerified(rail.getId(), true, actorId, "REGISTRY_ADMIN");

        assertThat(result.isMicarVerified()).isTrue();
        assertThat(result.getMicarVerifiedAt()).isNotNull();
        assertThat(result.getMicarVerifiedBy()).isEqualTo(actorId);
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_MICAR_VERIFIED");
    }

    @Test
    @DisplayName("setMicarVerified(false) clears the attestation (finding #8)")
    void setMicarVerified_clearsAttestation() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(java.time.Instant.now());
        rail.setMicarVerifiedBy(actorId);
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.setMicarVerified(rail.getId(), false, actorId, "REGISTRY_ADMIN");

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.getMicarVerifiedAt()).isNull();
        assertThat(result.getMicarVerifiedBy()).isNull();
        ArgumentCaptor<PaymentRailEvent> captor = ArgumentCaptor.forClass(PaymentRailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PAYMENT_RAIL_MICAR_VERIFICATION_CLEARED");
    }

    @Test
    @DisplayName("update resets a prior MiCAR attestation once the disclosed fields actually change (finding #8)")
    void update_resetsMicarVerification_whenDisclosureFieldsChange() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setMicarAuthorization("BaFin EMI 2024-01");
        rail.setEmtFlag(true);
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(java.time.Instant.now());
        rail.setMicarVerifiedBy(actorId);
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.update(rail.getId(), "New Name", PaymentRailType.STABLECOIN, "EUR",
                6, "updated", null, null, "BaFin EMI 2025-02", true, null, false,
                Map.of(), actorId, "REGISTRY_ADMIN", null);

        assertThat(result.isMicarVerified()).isFalse();
        assertThat(result.getMicarVerifiedAt()).isNull();
        assertThat(result.getMicarVerifiedBy()).isNull();
    }

    @Test
    @DisplayName("update leaves a prior MiCAR attestation intact when the disclosed fields are unchanged (finding #8)")
    void update_leavesMicarVerificationIntact_whenDisclosureFieldsUnchanged() {
        PaymentRail rail = new PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setMicarAuthorization("BaFin EMI 2024-01");
        rail.setEmtFlag(true);
        rail.setWhitePaperUrl("https://example.com/wp.pdf");
        rail.setRedemptionAtPar(true);
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(java.time.Instant.now());
        rail.setMicarVerifiedBy(actorId);
        when(railRepository.findById(rail.getId())).thenReturn(Optional.of(rail));

        PaymentRail result = service.update(rail.getId(), "New Display Name Only", PaymentRailType.STABLECOIN, "EUR",
                6, "updated description", null, null, "BaFin EMI 2024-01", true,
                "https://example.com/wp.pdf", true, Map.of(), actorId, "REGISTRY_ADMIN", null);

        assertThat(result.isMicarVerified()).isTrue();
        assertThat(result.getMicarVerifiedBy()).isEqualTo(actorId);
    }
}
