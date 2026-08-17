package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.api.DappReviewEvent;
import de.makibytes.registerwerk.marketplace.api.DappReviewEventRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.payment.events.PaymentRailEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRailAddressChangeListener — flags PUBLISHED listings on rail address drift")
class PaymentRailAddressChangeListenerTest {

    @Mock private DappPaymentMethodRepository paymentMethodRepository;
    @Mock private DappVersionRepository versionRepository;
    @Mock private DappReviewEventRepository reviewEventRepository;

    private PaymentRailAddressChangeListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentRailAddressChangeListener(paymentMethodRepository, versionRepository, reviewEventRepository);
    }

    private static DappPaymentMethod methodFor(UUID versionId) {
        DappPaymentMethod method = new DappPaymentMethod();
        method.setId(UUID.randomUUID());
        method.setVersionId(versionId);
        method.setMethodType(DappPaymentMethod.MethodType.RAIL);
        method.setRailCode("aueur");
        return method;
    }

    private static DappVersion versionWithStatus(UUID id, DappVersionStatus status) {
        DappVersion version = new DappVersion();
        version.setId(id);
        version.setStatus(status);
        return version;
    }

    @Test
    @DisplayName("ignores events other than address-changing UPDATED")
    void ignoresNonAddressChangeEvents() {
        listener.onPaymentRailEvent(new PaymentRailEvent("DISABLED", UUID.randomUUID(), UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("code", "aueur")));
        listener.onPaymentRailEvent(new PaymentRailEvent("UPDATED", UUID.randomUUID(), UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("code", "aueur")));

        verifyNoInteractions(paymentMethodRepository, versionRepository, reviewEventRepository);
    }

    @Test
    @DisplayName("records a review-trail entry for PUBLISHED versions referencing the changed rail")
    void recordsReviewEntryForPublishedVersion() {
        UUID versionId = UUID.randomUUID();
        when(paymentMethodRepository.findByRailCode("aueur")).thenReturn(List.of(methodFor(versionId)));
        when(versionRepository.findById(versionId))
                .thenReturn(Optional.of(versionWithStatus(versionId, DappVersionStatus.PUBLISHED)));

        listener.onPaymentRailEvent(new PaymentRailEvent("UPDATED", UUID.randomUUID(), UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("code", "aueur",
                        "oldChainAddresses", Map.of("chain1", "0xold"),
                        "newChainAddresses", Map.of("chain1", "0xnew"))));

        ArgumentCaptor<DappReviewEvent> captor = ArgumentCaptor.forClass(DappReviewEvent.class);
        verify(reviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().getVersionId()).isEqualTo(versionId);
        assertThat(captor.getValue().getAction()).isEqualTo("PAYMENT_RAIL_ADDRESS_DRIFT");
        assertThat(captor.getValue().getNotes()).contains("0xold").contains("0xnew");
    }

    @Test
    @DisplayName("skips versions that are not PUBLISHED (e.g. still DRAFT/SUBMITTED)")
    void skipsNonPublishedVersions() {
        UUID versionId = UUID.randomUUID();
        when(paymentMethodRepository.findByRailCode("aueur")).thenReturn(List.of(methodFor(versionId)));
        when(versionRepository.findById(versionId))
                .thenReturn(Optional.of(versionWithStatus(versionId, DappVersionStatus.DRAFT)));

        listener.onPaymentRailEvent(new PaymentRailEvent("UPDATED", UUID.randomUUID(), UUID.randomUUID(),
                "REGISTRY_ADMIN", Map.of("code", "aueur",
                        "oldChainAddresses", Map.of("chain1", "0xold"),
                        "newChainAddresses", Map.of("chain1", "0xnew"))));

        verifyNoInteractions(reviewEventRepository);
    }
}
