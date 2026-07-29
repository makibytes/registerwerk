package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.kyc.api.RegisterDocumentProfile;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegisterStatementPdfRenderer — jurisdiction labeling, §17 content, pagination")
class RegisterStatementPdfRendererTest {

    private final JurisdictionRequirementConfig jurisdictionConfig = new JurisdictionRequirementConfig();

    private Asset asset(de.makibytes.registerwerk.customer.api.Jurisdiction jurisdiction, OnchainLevel level) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setName("Test Bond 2026");
        asset.setIsin("DE000TESTB01");
        asset.setJurisdiction(jurisdiction);
        asset.setOnchainLevel(level);
        return asset;
    }

    private AssetHolder holder(EntryType entryType) {
        AssetHolder h = new AssetHolder();
        h.setId(UUID.randomUUID());
        h.setWalletAddress("0xabc123");
        h.setNominalAmount(new BigDecimal("1000"));
        h.setEntryType(entryType);
        h.setHolderReference("RW-REF-0007");
        return h;
    }

    private LegalEntity entity(String number, String name) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(number);
        e.setCurrentName(name);
        return e;
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("DE individual entry renders the statutory § 19 Registerauszug with no disclaimer")
    void individualDeStatutory() throws IOException {
        Asset asset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG, OnchainLevel.CONTROL);
        AssetHolder h = holder(EntryType.INDIVIDUAL);
        RegisterDocumentProfile profile = jurisdictionConfig.resolveRegisterDocumentProfile(asset.getJurisdiction(), true);

        byte[] pdf = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), null,
                List.of(), null, null, StatementTrigger.ANNUAL, profile, "Registerwerk eWpG-Registry", "DE",
                LocalDate.of(2026, 1, 15));

        String text = textOf(pdf);
        assertThat(profile.statutory()).isTrue();
        assertThat(text).contains("Registerauszug");
        assertThat(text).contains("§ 19 eWpG");
        assertThat(text).contains("Kryptowertpapierregister");
        assertThat(text).doesNotContain("Hinweis / Notice");
    }

    @Test
    @DisplayName("DE collective entry renders a holding confirmation, never labeled § 19")
    void collectiveDeIsHoldingConfirmationNotStatutory() throws IOException {
        Asset asset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG, OnchainLevel.CONTROL);
        AssetHolder h = holder(EntryType.COLLECTIVE);
        RegisterDocumentProfile profile = jurisdictionConfig.resolveRegisterDocumentProfile(asset.getJurisdiction(), false);

        byte[] pdf = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), null,
                List.of(), null, null, StatementTrigger.ON_DEMAND, profile, "Registerwerk eWpG-Registry", "DE",
                LocalDate.of(2026, 1, 15));

        String text = textOf(pdf);
        assertThat(profile.statutory()).isFalse();
        assertThat(text).contains("Bestandsbestätigung");
        assertThat(text).contains("Hinweis / Notice");
        assertThat(text).contains("nominee");
    }

    @Test
    @DisplayName("FR individual entry is statutory; LU/LI individual carry a local-counsel disclaimer")
    void frenchStatutoryVersusLuLiAnalogues() throws IOException {
        Asset frAsset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.FR_AMF, OnchainLevel.CONTROL);
        RegisterDocumentProfile frProfile = jurisdictionConfig.resolveRegisterDocumentProfile(frAsset.getJurisdiction(), true);
        assertThat(frProfile.statutory()).isTrue();
        byte[] frPdf = RegisterStatementPdfRenderer.render(frAsset, holder(EntryType.INDIVIDUAL),
                entity("INV-2", "Investisseur SARL"), null, List.of(), null, null,
                StatementTrigger.ON_DEMAND, frProfile, "Registerwerk eWpG-Registry", "FR", LocalDate.of(2026, 1, 15));
        assertThat(textOf(frPdf)).contains("Attestation d'inscription en compte");

        Asset luAsset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.LU_CSSF, OnchainLevel.CONTROL);
        RegisterDocumentProfile luProfile = jurisdictionConfig.resolveRegisterDocumentProfile(luAsset.getJurisdiction(), true);
        assertThat(luProfile.statutory()).isFalse();
        byte[] luPdf = RegisterStatementPdfRenderer.render(luAsset, holder(EntryType.INDIVIDUAL),
                entity("INV-3", "Investisseur Sàrl"), null, List.of(), null, null,
                StatementTrigger.ON_DEMAND, luProfile, "Registerwerk eWpG-Registry", "LU", LocalDate.of(2026, 1, 15));
        assertThat(textOf(luPdf)).contains("Attestation de détention de titres");
        assertThat(textOf(luPdf)).contains("Luxembourg counsel");

        Asset liAsset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.LI_TVTG, OnchainLevel.CONTROL);
        RegisterDocumentProfile liProfile = jurisdictionConfig.resolveRegisterDocumentProfile(liAsset.getJurisdiction(), true);
        assertThat(liProfile.statutory()).isFalse();
        byte[] liPdf = RegisterStatementPdfRenderer.render(liAsset, holder(EntryType.INDIVIDUAL),
                entity("INV-4", "Investor AG"), null, List.of(), null, null,
                StatementTrigger.ON_DEMAND, liProfile, "Registerwerk eWpG-Registry", "LI", LocalDate.of(2026, 1, 15));
        assertThat(textOf(liPdf)).contains("Token-Bestätigung");
        assertThat(textOf(liPdf)).contains("Liechtenstein counsel");
    }

    @Test
    @DisplayName("issuer, bond terms and term sheet are rendered when present; '—' placeholders otherwise")
    void issuerBondTermsAndTermSheetRendered() throws IOException {
        Asset asset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG, OnchainLevel.NONE);
        AssetHolder h = holder(EntryType.INDIVIDUAL);
        LegalEntity issuer = entity("ISS-1", "Acme Issuer AG");
        AssetBondTerms bondTerms = new AssetBondTerms();
        bondTerms.setFaceValue(new BigDecimal("500000"));
        bondTerms.setCurrencyIso("EUR");
        bondTerms.setIssueDate(LocalDate.of(2025, 1, 1));
        bondTerms.setMaturityDate(LocalDate.of(2030, 1, 1));
        bondTerms.setDayCount(DayCountConvention.ACT_365);
        bondTerms.setPaymentFrequency(PaymentFrequency.ANNUAL);
        AssetDocument termSheet = new AssetDocument();
        termSheet.setFileName("term-sheet.pdf");
        termSheet.setContentHash("0xdeadbeef");
        RegisterDocumentProfile profile = jurisdictionConfig.resolveRegisterDocumentProfile(asset.getJurisdiction(), true);

        byte[] withData = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), issuer,
                List.of(), bondTerms, termSheet, StatementTrigger.ANNUAL, profile,
                "Registerwerk eWpG-Registry", "DE", LocalDate.of(2026, 1, 15));
        String withDataText = textOf(withData);
        assertThat(withDataText).contains("Acme Issuer AG");
        assertThat(withDataText).contains("ISS-1");
        assertThat(withDataText).contains("500000");
        assertThat(withDataText).contains("EUR");
        assertThat(withDataText).contains("term-sheet.pdf");
        assertThat(withDataText).contains("0xdeadbeef");
        assertThat(withDataText).contains("Zentralregister");

        byte[] withoutData = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), null,
                List.of(), null, null, StatementTrigger.ANNUAL, profile,
                "Registerwerk eWpG-Registry", "DE", LocalDate.of(2026, 1, 15));
        String withoutDataText = textOf(withoutData);
        assertThat(withoutDataText).contains("nicht hinterlegt");
    }

    @Test
    @DisplayName("free-text §17(2) fields and structured Sperrvermerke both render")
    void freeTextAndStructuredBlocksRender() throws IOException {
        Asset asset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG, OnchainLevel.CONTROL);
        AssetHolder h = holder(EntryType.INDIVIDUAL);
        h.setThirdPartyRights("Pfandrecht zugunsten Bank X");
        h.setDisposalRestrictions("Zustimmungspflicht des Vormunds");
        h.setLegalCapacityNote("Beschränkt geschäftsfähig");
        RegisterDocumentProfile profile = jurisdictionConfig.resolveRegisterDocumentProfile(asset.getJurisdiction(), true);

        HolderBlock block = new HolderBlock();
        block.setBlockType(HolderBlock.BlockType.PFANDRECHT);
        block.setLegalBasis("§ 1204 BGB");
        block.setCourtRef("AG München 12 O 345/25");
        block.setStartsAt(Instant.parse("2025-06-01T00:00:00Z"));

        byte[] pdf = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), null,
                List.of(block), null, null, StatementTrigger.ANNUAL, profile,
                "Registerwerk eWpG-Registry", "DE", LocalDate.of(2026, 1, 15));

        String text = textOf(pdf);
        assertThat(text).contains("Pfandrecht zugunsten Bank X");
        assertThat(text).contains("Zustimmungspflicht des Vormunds");
        assertThat(text).contains("Beschränkt geschäftsfähig");
        assertThat(text).contains("PFANDRECHT");
        assertThat(text).contains("§ 1204 BGB");
        assertThat(text).contains("AG München 12 O 345/25");
    }

    @Test
    @DisplayName("a long list of Sperrvermerke overflows onto a second page instead of truncating")
    void manyBlocksOverflowToSecondPage() throws IOException {
        Asset asset = asset(de.makibytes.registerwerk.customer.api.Jurisdiction.DE_EWPG, OnchainLevel.CONTROL);
        AssetHolder h = holder(EntryType.INDIVIDUAL);
        RegisterDocumentProfile profile = jurisdictionConfig.resolveRegisterDocumentProfile(asset.getJurisdiction(), true);

        List<HolderBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            HolderBlock b = new HolderBlock();
            b.setBlockType(HolderBlock.BlockType.GERICHTSBESCHLUSS);
            b.setLegalBasis("Order #" + i);
            b.setStartsAt(Instant.now().minus(i, ChronoUnit.DAYS));
            blocks.add(b);
        }

        byte[] pdf = RegisterStatementPdfRenderer.render(asset, h, entity("INV-1", "Investor GmbH"), null,
                blocks, null, null, StatementTrigger.ANNUAL, profile,
                "Registerwerk eWpG-Registry", "DE", LocalDate.of(2026, 1, 15));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
        assertThat(textOf(pdf)).contains("Order #59");
    }
}
