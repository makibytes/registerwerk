package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.EddApprovalRepository;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KYC checklist captures real expiry/issue dates; evidence-gap report (6-15, 6-17)")
class KycEvidenceTest {

    private final KycDocumentRepository docs = mock(KycDocumentRepository.class);
    private final KycComplianceService compliance = new KycComplianceService(docs, new JurisdictionRequirementConfig());

    private KycDocument doc(KycDocument.DocumentType type, LocalDate issue, LocalDate expires) {
        KycDocument d = new KycDocument();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setDocumentType(type);
        d.setIssueDate(issue);
        d.setExpiresAt(expires);
        return d;
    }

    @Test
    @DisplayName("an expired document is not counted; an old issue date makes a register extract too old even if just uploaded")
    void expiredAndIssueDate() {
        UUID entityId = UUID.randomUUID();
        JurisdictionRequirementConfig.JurisdictionProfile profile =
                new JurisdictionRequirementConfig().getProfile(Jurisdiction.DE_EWPG);
        List<KycDocument> all = profile.issuerRequirements().stream().filter(r -> r.mandatory())
                .map(r -> doc(r.documentType(), null, LocalDate.now().plusYears(1))).toList();
        when(docs.findByLegalEntityIdAndDeletedAtIsNull(entityId)).thenReturn(all);
        assertThat(compliance.checkCompliance(entityId, Jurisdiction.DE_EWPG).fullyCompliant()).isTrue();

        KycDocument first = all.get(0);
        first.setExpiresAt(LocalDate.now().minusDays(1));
        KycComplianceService.ComplianceResult expired = compliance.checkCompliance(entityId, Jurisdiction.DE_EWPG);
        assertThat(expired.fullyCompliant()).isFalse();
        assertThat(expired.expiredCount()).isEqualTo(1);

        first.setExpiresAt(LocalDate.now().plusYears(1));
        profile.issuerRequirements().stream().filter(r -> r.maxAge() != null && r.mandatory()).findFirst().ifPresent(r -> {
            all.stream().filter(d -> d.getDocumentType() == r.documentType()).forEach(d ->
                    d.setIssueDate(LocalDate.now().minusYears(20)));
            assertThat(compliance.checkCompliance(entityId, Jurisdiction.DE_EWPG).tooOldCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the gap report lists an APPROVED entity with no documents, no beneficial owner and a 2099 expiry")
    void gapReport() {
        LegalEntityRepository entities = mock(LegalEntityRepository.class);
        BeneficialOwnerRepository owners = mock(BeneficialOwnerRepository.class);
        ScreeningGate gate = mock(ScreeningGate.class);
        LegalEntity entity = new LegalEntity();
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        entity.setKycStatus(KycStatus.APPROVED);
        entity.setKycExpiryDate(LocalDate.of(2099, 1, 1));
        when(entities.findByKycStatus(KycStatus.APPROVED)).thenReturn(List.of(entity));
        when(docs.findByLegalEntityIdAndDeletedAtIsNull(entity.getId())).thenReturn(List.of());
        when(owners.findByEntityIdAndCeasedAtIsNull(entity.getId())).thenReturn(List.of());

        KycEvidenceService service = new KycEvidenceService(entities, owners, mock(NaturalPersonRepository.class),
                mock(EddApprovalRepository.class), compliance, gate, 12);
        List<KycEvidenceService.Gap> gaps = service.evidenceGaps();

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).gaps()).anyMatch(g -> g.startsWith("CHECKLIST_INCOMPLETE"))
                .contains("NO_BENEFICIAL_OWNER", "EXPIRY_BEYOND_MAX_VALIDITY");
    }
}
