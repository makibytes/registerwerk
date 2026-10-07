package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecord;
import de.makibytes.registerwerk.kyc.api.KycApprovalRecordRepository;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 6-15 / 6-17: entity-level KYC approval runs the checklist, beneficial-owner coverage, validity cap and
 * PEP review cap. Each refusal below was an approval before the fix (an empty body approved any entity with a
 * clear screening, no documents, no beneficial owner and an expiry in 2099).
 */
@DisplayName("KycService.approveKyc - evidence gates (6-15, 6-17)")
class KycApprovalGateTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final BeneficialOwnerRepository owners = mock(BeneficialOwnerRepository.class);
    private final NaturalPersonRepository persons = mock(NaturalPersonRepository.class);
    private final EddApprovalRepository edds = mock(EddApprovalRepository.class);
    private final KycComplianceService compliance = mock(KycComplianceService.class);
    private final ScreeningGate gate = mock(ScreeningGate.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final KycApprovalRecordRepository records = mock(KycApprovalRecordRepository.class);

    private final KycEvidenceService evidence = new KycEvidenceService(entities, owners, persons, edds, compliance, gate, 12);
    private final KycService service = new KycService(entities, mock(KycJurisdictionApprovalRepository.class),
            publisher, gate, evidence, records);

    private final UUID entityId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private LegalEntity entity;

    @BeforeEach
    void setUp() {
        entity = new LegalEntity();
        entity.setStatus(EntityStatus.ACTIVE);
        entity.setRegistrationCountry("DE");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(compliance.checkCompliance(any(), any())).thenAnswer(inv -> checklist(true));
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of(owner(new BigDecimal("80"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, UUID.randomUUID())));
    }

    private static KycComplianceService.ComplianceResult checklist(boolean ok) {
        return new KycComplianceService.ComplianceResult(Jurisdiction.DE_EWPG, UUID.randomUUID(),
                List.of(new KycComplianceService.DocumentStatus(KycDocument.DocumentType.PASSPORT, true, "x", "x",
                        ok, false, false, Instant.now(), UUID.randomUUID())),
                ok, ok ? 0 : 1, 0, 0);
    }

    private static BeneficialOwner owner(BigDecimal pct, BeneficialOwner.ControlType type, UUID personId) {
        BeneficialOwner bo = new BeneficialOwner();
        bo.setEntityId(UUID.randomUUID());
        bo.setNaturalPersonId(personId);
        bo.setOwnershipPct(pct);
        bo.setControlType(type);
        return bo;
    }

    private void approve(LocalDate expiry, String note, boolean admin) {
        service.approveKyc(entityId, expiry, actor, approver, null, note, admin);
    }

    @Test
    @DisplayName("an entity without documents and an empty body is refused, an override needs REGISTRY_ADMIN")
    void incompleteChecklistNeedsOverrideByAdmin() {
        when(compliance.checkCompliance(any(), any())).thenAnswer(inv -> checklist(false));

        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overrideNote");
        assertThatThrownBy(() -> approve(null, "accepted risk", false)).isInstanceOf(AccessDeniedException.class);
        verify(entities, never()).save(any());

        approve(null, "accepted risk", true);
        ArgumentCaptor<KycApprovalRecord> rec = ArgumentCaptor.forClass(KycApprovalRecord.class);
        verify(records).save(rec.capture());
        assertThat(rec.getValue().isChecklistCompliant()).isFalse();
        assertThat(rec.getValue().getOverrideNote()).isEqualTo("accepted risk");
        assertThat(rec.getValue().getSecondApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("an entity without any beneficial owner is refused")
    void noBeneficialOwnerRefused() {
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of());
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("no beneficial owner");
        verify(entities, never()).save(any());
    }

    @Test
    @DisplayName("identified ownership below 75 % is refused unless a documented SMO fallback exists")
    void unexplainedOwnershipRefused() {
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of(owner(new BigDecimal("40"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, UUID.randomUUID())));
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("40");
    }

    @Test
    @DisplayName("an SMO-only file needs the REGISTRY_ADMIN override note")
    void smoFallbackNeedsOverride() {
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of(owner(null,
                BeneficialOwner.ControlType.SENIOR_MANAGING_OFFICIAL, UUID.randomUUID())));
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("senior-managing-official");
        approve(null, "no owner above threshold identifiable", true);
        ArgumentCaptor<KycApprovalRecord> rec = ArgumentCaptor.forClass(KycApprovalRecord.class);
        verify(records).save(rec.capture());
        assertThat(rec.getValue().isSmoFallback()).isTrue();
    }

    @Test
    @DisplayName("an expiry beyond registerwerk.kyc.max-validity-months (2099) is refused, the default is the cap")
    void expiryCapped() {
        assertThatThrownBy(() -> approve(LocalDate.of(2099, 1, 1), null, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum validity");

        approve(null, null, true);
        assertThat(entity.getKycExpiryDate()).isEqualTo(LocalDate.now().plusMonths(12));
        assertThat(entity.getKycStatus()).isEqualTo(KycStatus.APPROVED);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(ev.capture());
        KycApprovedEvent event = (KycApprovedEvent) ev.getValue();
        assertThat(event.payload()).containsKeys("checklistSnapshot", "jurisdiction", "identifiedOwnershipPct")
                .containsEntry("dualControlApproverId", approver.toString());
    }

    @Test
    @DisplayName("only ACTIVE / PENDING_ONBOARDING entities can be approved")
    void suspendedEntityRefused() {
        entity.setStatus(EntityStatus.SUSPENDED);
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("T6-12: a PENDING_REACTIVATION entity needs the full approval; success activates it and reuses the reactivation event; CLOSED stays refused")
    void pendingReactivationIsActivatedByFreshApproval() {
        entity.setStatus(EntityStatus.CLOSED);
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(InvalidStateTransitionException.class);

        entity.setStatus(EntityStatus.PENDING_REACTIVATION);
        entity.setKycStatus(de.makibytes.registerwerk.customer.api.KycStatus.NOT_STARTED);
        // gates still apply: an unresolved screening result refuses and leaves the entity pending
        when(gate.hasUnresolvedHit(entityId)).thenReturn(true);
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(ComplianceGateException.class);
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.PENDING_REACTIVATION);
        verify(entities, never()).save(any());

        when(gate.hasUnresolvedHit(entityId)).thenReturn(false);
        approve(null, null, true);
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(entity.getKycStatus()).isEqualTo(de.makibytes.registerwerk.customer.api.KycStatus.APPROVED);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(ev.capture());
        assertThat(ev.getAllValues().get(1)).isInstanceOf(de.makibytes.registerwerk.customer.events.EntityReactivatedEvent.class);
    }

    @Test
    @DisplayName("a confirmed PEP caps the expiry at the EDD review date and without EDD blocks approval")
    void pepCapAndMissingEdd() {
        UUID personId = UUID.randomUUID();
        when(owners.findByEntityIdAndCeasedAtIsNull(entityId)).thenReturn(List.of(owner(new BigDecimal("80"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, personId)));
        NaturalPerson pep = new NaturalPerson();
        ReflectionTestUtils.setField(pep, "id", personId);
        pep.setPepStatus(NaturalPerson.PepStatus.CONFIRMED_PEP);
        when(persons.findAllById(anyCollection())).thenReturn(List.of(pep));

        when(edds.findByNaturalPersonIdInAndReviewDueAfter(anyCollection(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> approve(null, null, true)).isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("PEP");

        Instant due = LocalDate.now().plusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant();
        EddApproval edd = new EddApproval();
        edd.setNaturalPersonId(personId);
        edd.setReviewDue(due);
        when(edds.findByNaturalPersonIdInAndReviewDueAfter(anyCollection(), any())).thenReturn(List.of(edd));
        approve(null, null, true);
        assertThat(entity.getKycExpiryDate()).isEqualTo(LocalDate.now().plusMonths(3));
    }
}
