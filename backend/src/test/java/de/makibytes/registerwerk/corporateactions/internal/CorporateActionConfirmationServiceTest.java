package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionConfirmationService unit tests")
class CorporateActionConfirmationServiceTest {

    @Mock private CorporateActionRepository actionRepository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private LegalEntityRepository entityRepository;
    @Mock private DocumentSigningService signingService;

    @InjectMocks
    private CorporateActionConfirmationService service;

    private static CorporateAction settledAction(UUID id) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", id);
        ca.setAssetId(UUID.randomUUID());
        ca.setActionType(CorporateAction.ActionType.COUPON);
        ca.setStatus(CorporateAction.Status.SETTLED);
        return ca;
    }

    private static CorporateActionEntry entry(UUID corporateActionId, UUID investorId, BigDecimal entitlement) {
        CorporateActionEntry e = new CorporateActionEntry();
        e.setCorporateActionId(corporateActionId);
        e.setInvestorId(investorId);
        e.setWalletAddress("0x1111111111111111111111111111111111111111");
        e.setNominalAtRecord(new BigDecimal("1000"));
        e.setEntitlementAmount(entitlement);
        return e;
    }

    @Test
    @DisplayName("rejects generating a confirmation for an action that hasn't settled")
    void rejectsUnsettledAction() {
        UUID actionId = UUID.randomUUID();
        CorporateAction announced = new CorporateAction();
        ReflectionTestUtils.setField(announced, "id", actionId);
        announced.setStatus(CorporateAction.Status.ANNOUNCED);
        when(actionRepository.findById(actionId)).thenReturn(Optional.of(announced));

        assertThatThrownBy(() -> service.generateForOperator(actionId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("investor confirmation is scoped to only that investor's own entry")
    void investorConfirmation_scopedToOwnEntry() throws Exception {
        UUID actionId = UUID.randomUUID();
        UUID investorA = UUID.randomUUID();
        UUID investorB = UUID.randomUUID();
        CorporateAction ca = settledAction(actionId);

        when(actionRepository.findById(actionId)).thenReturn(Optional.of(ca));
        when(entryRepository.findByCorporateActionId(actionId)).thenReturn(List.of(
                entry(actionId, investorA, new BigDecimal("45.00")),
                entry(actionId, investorB, new BigDecimal("90.00"))
        ));
        when(assetRepository.findById(ca.getAssetId())).thenReturn(Optional.empty());
        when(entityRepository.findById(investorA)).thenReturn(Optional.empty());

        byte[] pdf = service.generateForInvestor(actionId, investorA);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("45.00");
            assertThat(text).doesNotContain("90.00");
        }
    }

    @Test
    @DisplayName("investor confirmation throws when the investor has no entry for this action")
    void investorConfirmation_throwsWhenNoOwnEntry() {
        UUID actionId = UUID.randomUUID();
        UUID otherInvestor = UUID.randomUUID();
        UUID requestingInvestor = UUID.randomUUID();
        CorporateAction ca = settledAction(actionId);

        when(actionRepository.findById(actionId)).thenReturn(Optional.of(ca));
        when(entryRepository.findByCorporateActionId(actionId))
                .thenReturn(List.of(entry(actionId, otherInvestor, new BigDecimal("45.00"))));

        assertThatThrownBy(() -> service.generateForInvestor(actionId, requestingInvestor))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
