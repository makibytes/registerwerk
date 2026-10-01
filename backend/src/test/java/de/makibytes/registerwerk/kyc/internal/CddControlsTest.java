package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerCeasedEvent;
import de.makibytes.registerwerk.kyc.events.NaturalPersonPepStatusChangedEvent;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.screening.events.ScreeningPepConfirmedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 6-16 / 6-17: beneficial-owner cease, ownership validation, verification, EDD and PEP status. */
@DisplayName("Beneficial-owner and PEP controls (6-16, 6-17)")
class CddControlsTest {

    private final BeneficialOwnerRepository owners = mock(BeneficialOwnerRepository.class);
    private final NaturalPersonRepository persons = mock(NaturalPersonRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ScreeningGate gate = mock(ScreeningGate.class);
    private final EntityTaskPort tasks = mock(EntityTaskPort.class);
    private final KycDocumentRepository docs = mock(KycDocumentRepository.class);
    private final EddApprovalRepository edds = mock(EddApprovalRepository.class);
    private final BeneficialOwnerService service = new BeneficialOwnerService(owners, persons, entities, publisher,
            gate, tasks, docs, edds, mock(KycEvidenceService.class));

    private final UUID entityId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();
    private final UUID boId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private LegalEntity entity;
    private BeneficialOwner bo;

    @BeforeEach
    void setUp() {
        entity = new LegalEntity();
        ReflectionTestUtils.setField(entity, "id", entityId);
        entity.setKycStatus(KycStatus.APPROVED);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        bo = new BeneficialOwner();
        bo.setEntityId(entityId);
        bo.setNaturalPersonId(personId);
        bo.setControlType(BeneficialOwner.ControlType.DIRECT_OWNERSHIP);
        ReflectionTestUtils.setField(bo, "id", boId);
        when(owners.findByIdAndEntityId(boId, entityId)).thenReturn(Optional.of(bo));
        when(owners.save(any(BeneficialOwner.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private BeneficialOwnerRequest.NaturalPersonInput person() {
        return new BeneficialOwnerRequest.NaturalPersonInput("Jane", "Doe", null, "DE", "DE",
                null, null, null, null, null, null, "DE");
    }

    @Test
    @DisplayName("6-16: ceasing a UBO whose hit is open is refused - the gate cannot be emptied by one actor")
    void ceaseRefusedWhileScreeningUnresolved() {
        when(gate.hasUnresolvedHitForPerson(personId)).thenReturn(true);

        assertThatThrownBy(() -> service.ceaseBeneficialOwner(entityId, boId, "sold", null, actor, "REGISTRY_ADMIN", approver))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("screening");
        assertThat(bo.getCeasedAt()).isNull();
        verify(owners, never()).save(any());
    }

    @Test
    @DisplayName("6-16: cease needs a reason, records it with the approver and opens a review task on an APPROVED entity")
    void ceaseRecordsReasonAndOpensReviewTask() {
        UUID docId = UUID.randomUUID();
        when(docs.findByIdAndLegalEntityIdAndDeletedAtIsNull(docId, entityId)).thenReturn(Optional.of(new KycDocument()));

        assertThatThrownBy(() -> service.ceaseBeneficialOwner(entityId, boId, " ", null, actor, "REGISTRY_ADMIN", approver))
                .isInstanceOf(IllegalArgumentException.class);

        service.ceaseBeneficialOwner(entityId, boId, "shares sold 2026-09", docId, actor, "REGISTRY_ADMIN", approver);

        assertThat(bo.getCeasedAt()).isNotNull();
        assertThat(bo.getCeasedBy()).isEqualTo(actor);
        assertThat(bo.getCeaseReason()).isEqualTo("shares sold 2026-09");
        assertThat(bo.getCeaseDocumentId()).isEqualTo(docId);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(ev.capture());
        BeneficialOwnerCeasedEvent event = (BeneficialOwnerCeasedEvent) ev.getValue();
        assertThat(event.payload()).containsEntry("reason", "shares sold 2026-09")
                .containsEntry("dualControlApproverId", approver.toString());
        verify(tasks).open(eq(entityId), eq(EntityTask.KYC_REVIEW_REQUIRED), eq("BO_CEASED:" + boId), any(), eq(actor));
    }

    @Test
    @DisplayName("6-17: ownership 250 / 0 / -5, a sum above 100 and an SMO without reason are refused")
    void ownershipValidated() {
        for (String bad : List.of("250", "0", "-5")) {
            assertThatThrownBy(() -> service.addBeneficialOwner(entityId, person(), new BigDecimal(bad),
                    BeneficialOwner.ControlType.DIRECT_OWNERSHIP, null, null, actor, "REGISTRY_ADMIN"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownershipPct");
        }
        BeneficialOwner existing = new BeneficialOwner();
        existing.setControlType(BeneficialOwner.ControlType.DIRECT_OWNERSHIP);
        existing.setOwnershipPct(new BigDecimal("70"));
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.addBeneficialOwner(entityId, person(), new BigDecimal("40"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, null, null, actor, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceed 100");
        assertThatThrownBy(() -> service.addBeneficialOwner(entityId, person(), null,
                BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL, null, " ", actor, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        verify(persons, never()).save(any());
    }

    @Test
    @DisplayName("6-15: adding a UBO to an APPROVED entity opens a KYC_REVIEW_REQUIRED task")
    void addOnApprovedEntityOpensTask() {
        when(persons.save(any(NaturalPerson.class))).thenAnswer(inv -> {
            NaturalPerson p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", personId);
            return p;
        });
        service.addBeneficialOwner(entityId, person(), new BigDecimal("30"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, null, null, actor, "REGISTRY_ADMIN");
        verify(tasks).open(eq(entityId), eq(EntityTask.KYC_REVIEW_REQUIRED), any(), any(), eq(actor));
    }

    @Test
    @DisplayName("6-17: verify stores verifier, time and evidence document; an expired document is refused")
    void verifyUsesStoredDocument() {
        UUID docId = UUID.randomUUID();
        KycDocument doc = new KycDocument();
        doc.setDocumentType(KycDocument.DocumentType.BENEFICIAL_OWNER_REGISTER_EXTRACT);
        doc.setExpiresAt(LocalDate.now().minusDays(1));
        when(docs.findByIdAndLegalEntityIdAndDeletedAtIsNull(docId, entityId)).thenReturn(Optional.of(doc));
        assertThatThrownBy(() -> service.verifyBeneficialOwner(entityId, boId, docId, actor, "COMPLIANCE_OFFICER"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expired");

        doc.setExpiresAt(LocalDate.now().plusDays(30));
        service.verifyBeneficialOwner(entityId, boId, docId, actor, "COMPLIANCE_OFFICER");
        assertThat(bo.getVerifiedBy()).isEqualTo(actor);
        assertThat(bo.getVerifiedAt()).isNotNull();
        assertThat(bo.getVerificationDocumentId()).isEqualTo(docId);
    }

    private NaturalPerson person(NaturalPerson.PepStatus status) {
        NaturalPerson p = new NaturalPerson();
        ReflectionTestUtils.setField(p, "id", personId);
        p.setPepStatus(status);
        when(persons.findById(personId)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("6-17: EDD needs a confirmed PEP and a different second approver, is capped at 6 months and tells the gate")
    void eddApproval() {
        person(NaturalPerson.PepStatus.UNKNOWN);
        assertThatThrownBy(() -> service.approveEdd(entityId, boId, "ok", null, actor, "REGISTRY_ADMIN", approver))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("confirmed PEP");

        person(NaturalPerson.PepStatus.CONFIRMED_PEP);
        assertThatThrownBy(() -> service.approveEdd(entityId, boId, "ok", null, actor, "REGISTRY_ADMIN", actor))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("second approver");
        assertThatThrownBy(() -> service.approveEdd(entityId, boId, "ok", LocalDate.now().plusMonths(8), actor, "REGISTRY_ADMIN", approver))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6 months");

        when(edds.save(any(EddApproval.class))).thenAnswer(inv -> {
            EddApproval e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });
        when(gate.recordPepEddApproval(eq(personId), any(), any(), eq(actor), eq("REGISTRY_ADMIN"), eq(approver))).thenReturn(1);

        EddApproval saved = service.approveEdd(entityId, boId, "source of wealth documented", null, actor, "REGISTRY_ADMIN", approver);

        assertThat(saved.getSecondApproverId()).isEqualTo(approver);
        assertThat(saved.getReviewDue()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(170)))
                .isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(190)));
        verify(gate).recordPepEddApproval(eq(personId), eq(saved.getId()), eq(saved.getReviewDue()), eq(actor), any(), eq(approver));
    }

    @Test
    @DisplayName("6-17: a confirmed PEP hit writes pepStatus=CONFIRMED_PEP (setPepStatus had no caller) and a review task")
    void pepConfirmationWritesStatus() {
        NaturalPerson p = person(NaturalPerson.PepStatus.UNKNOWN);
        when(owners.findByNaturalPersonId(personId)).thenReturn(List.of(bo));
        PepConfirmationListener listener = new PepConfirmationListener(persons, owners, entities, tasks, publisher);

        listener.onPepConfirmed(new ScreeningPepConfirmedEvent(UUID.randomUUID(), personId, actor, "COMPLIANCE_OFFICER",
                approver, Map.of()));

        assertThat(p.getPepStatus()).isEqualTo(NaturalPerson.PepStatus.CONFIRMED_PEP);
        assertThat(p.getPepStatusUpdatedAt()).isNotNull();
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(NaturalPersonPepStatusChangedEvent.class);
        verify(tasks).open(eq(entityId), eq(EntityTask.KYC_REVIEW_REQUIRED), eq("PEP:" + personId), any(), any());
    }
}
