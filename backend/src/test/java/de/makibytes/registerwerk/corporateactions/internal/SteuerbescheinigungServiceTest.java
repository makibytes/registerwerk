package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction.ActionType;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T3-03 interim: the "Steuerbescheinigung" became an informational Ertragsaufstellung. */
class SteuerbescheinigungServiceTest {

    private final UUID investor = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private CorporateActionEntryRepository entryRepo;
    private CorporateActionRepository actionRepo;
    private SteuerbescheinigungService service;
    private final List<CorporateActionEntry> entries = new java.util.ArrayList<>();
    private final List<CorporateAction> actions = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        entryRepo = mock(CorporateActionEntryRepository.class);
        actionRepo = mock(CorporateActionRepository.class);
        AssetRepository assetRepo = mock(AssetRepository.class);
        LegalEntityRepository entityRepo = mock(LegalEntityRepository.class);
        DocumentSigningService signing = mock(DocumentSigningService.class);
        FinalityGate gate = mock(FinalityGate.class);
        LegalEntity entity = new LegalEntity();
        when(entityRepo.findById(investor)).thenReturn(java.util.Optional.of(entity));
        when(entryRepo.findSettledByInvestorAndPeriod(eq(investor), any(), any())).thenReturn(entries);
        when(actionRepo.findAllById(anyIterable())).thenReturn(actions);
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setIsin("DE000TEST001");
        when(assetRepo.findAllById(anyIterable())).thenReturn(List.of(asset));
        service = new SteuerbescheinigungService(entryRepo, actionRepo, assetRepo, entityRepo, signing, gate,
                new de.makibytes.registerwerk.shared.RegisterClock(java.time.Clock.systemUTC(), java.time.ZoneId.of("Europe/Berlin")),
                "Op", "");
    }

    private void add(ActionType type, String currency, String amount) {
        CorporateAction a = new CorporateAction();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setAssetId(assetId);
        a.setActionType(type);
        a.setCurrency(currency);
        actions.add(a);
        CorporateActionEntry e = new CorporateActionEntry();
        e.setCorporateActionId(a.getId());
        e.setInvestorId(investor);
        e.setEntitlementAmount(new BigDecimal(amount));
        e.setSettledAt(Instant.now());
        entries.add(e);
    }

    private String text() throws Exception {
        byte[] pdf = service.generate(investor, 2025);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void excludesRedemptionPrincipal() throws Exception {
        add(ActionType.COUPON, "EUR", "50.00");
        add(ActionType.REDEMPTION, "EUR", "1000.00");
        add(ActionType.CALL, "EUR", "1020.00");
        add(ActionType.CAPITAL_CALL, "EUR", "300.00");
        String t = text();
        assertThat(t).contains("Summe Erträge EUR: 50.00 EUR");
        assertThat(t).doesNotContain("1050.00").doesNotContain("1000.00").doesNotContain("1020.00");
    }

    @Test
    void doesNotSumAcrossCurrencies() throws Exception {
        add(ActionType.COUPON, "EUR", "50.00");
        add(ActionType.COUPON, "USD", "70.00");
        String t = text();
        assertThat(t).contains("Summe Erträge EUR: 50.00 EUR").contains("Summe Erträge USD: 70.00 USD");
        assertThat(t).doesNotContain("120.00");
    }

    @Test
    void printsNoComputedKest() throws Exception {
        add(ActionType.COUPON, "EUR", "100.00");
        String t = text();
        assertThat(t).contains("Keine Steuerbescheinigung").contains("einbehalten: 0,00");
        assertThat(t).doesNotContain("25%").doesNotContain("5,5%").doesNotContain("45a Abs. 2")
                .doesNotContain("25.00");
    }

    @Test
    void paginatesInsteadOfDroppingLinesAndYearWindowIsRegisterZone() throws Exception {
        for (int i = 0; i < 90; i++) {
            add(ActionType.COUPON, String.format("X%02d", i), "1.00");
        }
        byte[] pdf = service.generate(investor, 2025);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String t = new PDFTextStripper().getText(doc);
            // every line and every total is on the document
            for (int i = 0; i < 90; i++) {
                assertThat(t).contains("Summe Erträge " + String.format("X%02d", i));
                assertThat(t).contains("1.00 " + String.format("X%02d", i));
            }
        }
        org.mockito.ArgumentCaptor<Instant> from = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(entryRepo).findSettledByInvestorAndPeriod(eq(investor), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2024-12-31T23:00:00Z")); // 2025-01-01 00:00 Berlin
    }
}
