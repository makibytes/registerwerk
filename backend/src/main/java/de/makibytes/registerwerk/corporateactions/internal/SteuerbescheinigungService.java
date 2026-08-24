package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates Steuerbescheinigung (annual tax certificate) PDFs per DE §43 KStG / §32d EStG.
 * Required for German investors to declare capital gains from electronic securities.
 * Issued annually for the prior tax year; also on-demand per investor request.
 *
 * <p>Income figures come from settled {@link CorporateActionEntry} rows — this used to be a
 * pure-placeholder document ("— (Coupon-Service ermittelt)" in every row, hardcoded "0,00 EUR"
 * withholding) because corporate actions never carried a per-holder payout breakdown at all.
 * Now: KESt (Kapitalertragsteuer, §43a Abs. 1 Nr. 1 EStG — 25%) and SolZ (Solidaritätszuschlag —
 * 5.5% of KESt) are computed from the actual settled income; church tax (KiSt) is honestly
 * reported as "not tracked" rather than falsely asserted "nein", since no field anywhere records
 * an investor's church-tax liability.
 */
@Service
public class SteuerbescheinigungService {

    private static final Logger log = LoggerFactory.getLogger(SteuerbescheinigungService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final BigDecimal KEST_RATE = new BigDecimal("0.25");
    private static final BigDecimal SOLZ_RATE = new BigDecimal("0.055");

    private final CorporateActionEntryRepository entryRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final DocumentSigningService signingService;
    private final FinalityGate finalityGate;
    private final String operatorName;
    private final String operatorTaxId;

    SteuerbescheinigungService(CorporateActionEntryRepository entryRepository,
                                CorporateActionRepository corporateActionRepository,
                                AssetRepository assetRepository,
                                LegalEntityRepository entityRepository,
                                DocumentSigningService signingService,
                                FinalityGate finalityGate,
                                @Value("${registerwerk.operator.name:}") String operatorName,
                                @Value("${registerwerk.operator.tax-id:}") String operatorTaxId) {
        this.entryRepository = entryRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.signingService = signingService;
        this.finalityGate = finalityGate;
        this.operatorName = operatorName;
        this.operatorTaxId = operatorTaxId;
    }

    /** One tax-relevant income line — one asset's aggregate settled income for the tax year. */
    private record IncomeLine(Asset asset, BigDecimal grossIncome) {}

    /**
     * Generates a Steuerbescheinigung PDF for the given entity and tax year.
     * @param entityId  the investor / holder legal entity
     * @param taxYear   the calendar year (e.g. 2025)
     */
    @Transactional(readOnly = true)
    public byte[] generate(UUID entityId, int taxYear) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        Instant yearStart = LocalDate.of(taxYear, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yearEnd = LocalDate.of(taxYear + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<CorporateActionEntry> entries = entryRepository.findSettledByInvestorAndPeriod(entityId, yearStart, yearEnd);
        List<IncomeLine> incomeLines = aggregateByAsset(entries);

        log.info("Generating Steuerbescheinigung for entity={} taxYear={} incomeLines={}", entityId, taxYear, incomeLines.size());

        try {
            byte[] unsigned = buildPdf(entity, incomeLines, taxYear);
            return signingService.isConfigured()
                    ? signingService.signPdf(unsigned, "Steuerbescheinigung " + taxYear + " — " + entity.getEntityNumber())
                    : unsigned;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Steuerbescheinigung PDF", e);
        }
    }

    private List<IncomeLine> aggregateByAsset(List<CorporateActionEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<UUID> corporateActionIds = entries.stream().map(CorporateActionEntry::getCorporateActionId).distinct().toList();
        Map<UUID, UUID> assetIdByAction = corporateActionRepository.findAllById(corporateActionIds).stream()
                .collect(Collectors.toMap(CorporateAction::getId, CorporateAction::getAssetId));

        Map<UUID, BigDecimal> grossByAsset = new LinkedHashMap<>();
        for (CorporateActionEntry entry : entries) {
            UUID assetId = assetIdByAction.get(entry.getCorporateActionId());
            if (assetId == null || entry.getEntitlementAmount() == null) {
                continue;
            }
            grossByAsset.merge(assetId, entry.getEntitlementAmount(), BigDecimal::add);
        }

        Map<UUID, Asset> assetById = assetRepository.findAllById(grossByAsset.keySet()).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

        // A tax certificate spans multiple assets, so GatedOperation#TAX_CERTIFICATE_ISSUE is
        // checked per distinct assetId rather than once for the whole document — there is
        // nothing meaningful a single call could pass.
        for (UUID assetId : grossByAsset.keySet()) {
            TokenStandard standard = assetById.containsKey(assetId) ? assetById.get(assetId).getTokenStandard() : null;
            finalityGate.require(GatedOperation.TAX_CERTIFICATE_ISSUE, assetId, standard, FinalityLevel.FINALIZED);
        }

        return grossByAsset.entrySet().stream()
                .map(e -> new IncomeLine(assetById.get(e.getKey()), e.getValue()))
                .toList();
    }

    private byte[] buildPdf(LegalEntity entity, List<IncomeLine> incomeLines, int taxYear) throws IOException {
        BigDecimal totalIncome = incomeLines.stream().map(IncomeLine::grossIncome).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal kest = totalIncome.multiply(KEST_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal solz = kest.multiply(SOLZ_RATE).setScale(2, RoundingMode.HALF_UP);

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                float margin = 50;
                float y = PDRectangle.A4.getHeight() - margin;

                // Header
                PdfHelper.writeText(content, margin, y, fontBold, 16, "Steuerbescheinigung");
                y -= 20;
                PdfHelper.writeText(content, margin, y, fontRegular, 10,
                    "gemäß §43 KStG / §32d EStG — Veranlagungszeitraum " + taxYear);
                y -= 8;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    "Ausgestellt am: " + LocalDate.now().format(DATE_FMT));
                y -= 20;

                // Issuer info
                PdfHelper.writeText(content, margin, y, fontBold, 11, "Ausstellendes Institut (Registerführer):");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    operatorName != null && !operatorName.isBlank() ? operatorName : "Registerwerk eWpG-Registry");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    "Steuer-ID / LEI: " + (operatorTaxId != null && !operatorTaxId.isBlank() ? operatorTaxId : "nicht konfiguriert (registerwerk.operator.tax-id)"));
                y -= 20;

                // Holder info
                PdfHelper.writeText(content, margin, y, fontBold, 11, "Inhaber / Depotinhaber:");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    "Registrierungsnummer: " + entity.getEntityNumber());
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    "Sitzland: " + entity.getRegistrationCountry());
                y -= 20;

                // Holdings / income
                PdfHelper.writeText(content, margin, y, fontBold, 11,
                    "Kapitalerträge aus elektronischen Wertpapieren (§ 43 Abs. 1 Nr. 1 KStG):");
                y -= 5;
                content.moveTo(margin, y);
                content.lineTo(PDRectangle.A4.getWidth() - margin, y);
                content.stroke();
                y -= 14;

                float[] cols = {margin, 260, 400};
                PdfHelper.writeText(content, cols[0], y, fontBold, 9, "Wertpapier / ISIN");
                PdfHelper.writeText(content, cols[1], y, fontBold, 9, "Kupon / Erträge EUR");
                PdfHelper.writeText(content, cols[2], y, fontBold, 9, "Quelle");
                y -= 12;

                if (incomeLines.isEmpty()) {
                    PdfHelper.writeText(content, cols[0], y, fontRegular, 8,
                            "Keine im Veranlagungszeitraum " + taxYear + " abgerechneten Kapitalerträge.");
                    y -= 12;
                } else {
                    for (IncomeLine line : incomeLines) {
                        String assetDesc = line.asset() != null
                            ? (line.asset().getIsin() != null ? line.asset().getIsin() : line.asset().getName())
                            : "(Asset gelöscht)";
                        PdfHelper.writeText(content, cols[0], y, fontRegular, 8, PdfHelper.truncate(assetDesc, 32));
                        PdfHelper.writeText(content, cols[1], y, fontRegular, 8, line.grossIncome().setScale(2, RoundingMode.HALF_UP).toPlainString());
                        PdfHelper.writeText(content, cols[2], y, fontRegular, 8, "Coupon-Service (settled)");
                        y -= 12;
                        if (y < margin + 100) break;
                    }
                }

                // Tax summary
                y -= 10;
                content.moveTo(margin, y);
                content.lineTo(PDRectangle.A4.getWidth() - margin, y);
                content.stroke();
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontBold, 10,
                        "Summe Kapitalerträge: " + totalIncome.setScale(2, RoundingMode.HALF_UP).toPlainString() + " EUR");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontBold, 10, "Kapitalertragsteuer (KESt, 25%): " + kest.toPlainString() + " EUR");
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontBold, 10, "Solidaritätszuschlag (SolZ, 5,5% der KESt): " + solz.toPlainString() + " EUR");
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                        "Kirchensteuer (KiSt): nicht erfasst — Registerwerk führt kein KiSt-Merkmal je Anleger.");
                y -= 20;

                // Footer
                String signatureClaim = signingService.isConfigured()
                        ? "Dieses Dokument wird digital signiert (PAdES-B-B, CMS/PKCS#7)."
                        : "Dieses Dokument ist NICHT digital signiert (kein Signaturzertifikat konfiguriert).";
                PdfHelper.writeText(content, margin, margin + 20, fontRegular, 7,
                    "Diese Steuerbescheinigung wurde maschinell erstellt und ist ohne Unterschrift gültig " +
                    "(§ 45a Abs. 2 EStG). " + signatureClaim + " Registerwerk eWpG-Registry.");
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
