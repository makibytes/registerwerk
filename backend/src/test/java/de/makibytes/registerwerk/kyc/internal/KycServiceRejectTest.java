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
    private final KycJurisdictionApprovalRepository approvals = mock(KycJurisdictionApprovalRepository.class);
    private final KycService service = new KycService(entities, approvals,
            publisher, mock(ScreeningGate.class), mock(KycEvidenceService.class),
            mock(de.makibytes.registerwerk.kyc.api.KycApprovalRecordRepository.class));

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

    @Test
    @DisplayName("Wave 5b: a jurisdiction rejection stores only the fixed category customer-side; the free text is audit-only")
    void jurisdictionRejectionKeepsTheFreeTextOperatorOnly() {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.of(new LegalEntity()));
        when(approvals.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        var saved = service.rejectKycForJurisdiction(id, de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG,
                "UBO appears on an internal watchlist", KycRejectionCategory.INFORMATION_INCONSISTENT, UUID.randomUUID());

        assertThat(saved.getRejectionReason()).isEqualTo("INFORMATION_INCONSISTENT").doesNotContain("watchlist");
        assertThat(de.makibytes.registerwerk.kyc.web.dto.KycJurisdictionApprovalResponse.from(saved).rejectionReason())
                .isEqualTo("INFORMATION_INCONSISTENT");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        var event = (de.makibytes.registerwerk.kyc.events.KycJurisdictionRejectedEvent) captor.getValue();
        assertThat(event.payload()).containsEntry("internalReason", "UBO appears on an internal watchlist")
                .containsEntry("reasonCode", "INFORMATION_INCONSISTENT").doesNotContainKey("reason");
    }

    @Test
    @DisplayName("legacy rows holding the operator's free text read as CONTACT_SUPPORT on the customer-visible response")
    void legacyFreeTextIsNeverShown() {
        var legacy = new de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval();
        legacy.setJurisdiction(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG);
        legacy.setStatus(de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval.Status.REJECTED);
        legacy.setRejectionReason("UBO appears on an internal watchlist");
        assertThat(de.makibytes.registerwerk.kyc.web.dto.KycJurisdictionApprovalResponse.from(legacy).rejectionReason())
                .isEqualTo("CONTACT_SUPPORT");
    }
}
