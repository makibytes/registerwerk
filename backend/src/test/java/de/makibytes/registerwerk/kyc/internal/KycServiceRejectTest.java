package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectionCategory;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KycService.rejectKyc — internal reason vs customer category (5C-09)")
class KycServiceRejectTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final KycService service = new KycService(entities, mock(KycJurisdictionApprovalRepository.class),
            publisher, mock(ScreeningGate.class));

    private KycRejectedEvent reject(KycRejectionCategory category) {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.of(new LegalEntity()));
        service.rejectKyc(id, "sanctions match", category, UUID.randomUUID());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return (KycRejectedEvent) captor.getValue();
    }

    @Test
    @DisplayName("the free text is kept for the audit trail under internalReason; the customer-facing code is separate")
    void internalReasonSeparateFromCode() {
        KycRejectedEvent event = reject(KycRejectionCategory.DOCUMENTS_UNREADABLE);
        assertThat(event.payload()).containsEntry("internalReason", "sanctions match")
                .containsEntry("reasonCode", "DOCUMENTS_UNREADABLE").doesNotContainKey("reason");
    }

    @Test
    @DisplayName("no category defaults to CONTACT_SUPPORT")
    void defaultsToContactSupport() {
        assertThat(reject(null).payload()).containsEntry("reasonCode", "CONTACT_SUPPORT");
    }
}
