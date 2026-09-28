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
import de.makibytes.registerwerk.shared.RegisterClock;
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
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates the annual "Ertragsaufstellung" (income statement) for an investor. Despite the
 * historic class/endpoint name this is <b>not</b> a Steuerbescheinigung within the meaning of
 * § 45a EStG (T3-03 interim): Registerwerk does not withhold Kapitalertragsteuer/Solidaritäts-
 * zuschlag on payouts (coupons are paid gross), so certifying computed KESt/SolZ would present
 * unwithheld tax as withheld. Whether the registrar is an auszahlende Stelle that withholds is a
 * parked policy question.
 *
 * <p>Only recurring income actions (COUPON, INTEREST_PAYMENT, DIVIDEND) are listed. Principal
 * repayments (REDEMPTION, PARTIAL_REDEMPTION, CALL) and capital calls are not income and are
 * excluded; realised gains are not determined (acquisition cost is not held in the register).
 * Amounts are grouped per currency — a sum across currencies is never formed.
 */
@Service
public class SteuerbescheinigungService {

    private static final Logger log = LoggerFactory.getLogger(SteuerbescheinigungService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Action types whose settled entitlement is investment income (never principal / capital calls). */
    static final java.util.Set<CorporateAction.ActionType> INCOME_TYPES = java.util.EnumSet.of(
            CorporateAction.ActionType.COUPON, CorporateAction.ActionType.INTEREST_PAYMENT,
            CorporateAction.ActionType.DIVIDEND);

    private final CorporateActionEntryRepository entryRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final DocumentSigningService signingService;
    private final FinalityGate finalityGate;
    private final RegisterClock registerClock;
    private final String operatorName;
    private final String operatorTaxId;

    SteuerbescheinigungService(CorporateActionEntryRepository entryRepository,
                                CorporateActionRepository corporateActionRepository,
                                AssetRepository assetRepository,
                                LegalEntityRepository entityRepository,
                                DocumentSigningService signingService,
                                FinalityGate finalityGate,
                                RegisterClock registerClock,
                                @Value("${registerwerk.operator.name:}") String operatorName,
                                @Value("${registerwerk.operator.tax-id:}") String operatorTaxId) {
        this.entryRepository = entryRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.signingService = signingService;
        this.finalityGate = finalityGate;
        this.registerClock = registerClock;
        this.operatorName = operatorName;
        this.operatorTaxId = operatorTaxId;
    }

    /** One income line — one asset's aggregate settled income in one currency for the year. */
    private record IncomeLine(Asset asset, String currency, BigDecimal grossIncome) {}

    /**
     * Generates a Steuerbescheinigung PDF for the given entity and tax year.
     * @param entityId  the investor / holder legal entity
     * @param taxYear   the calendar year (e.g. 2025)
     */
    @Transactional(readOnly = true)
    public byte[] generate(UUID entityId, int taxYear) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        // The calendar year is the register's (a payout settled 00:30 CET on 1 Jan belongs to the new year).
        Instant yearStart = LocalDate.of(taxYear, 1, 1).atStartOfDay(registerClock.registerZone()).toInstant();
        Instant yearEnd = LocalDate.of(taxYear + 1, 1, 1).atStartOfDay(registerClock.registerZone()).toInstant();
        List<CorporateActionEntry> entries = entryRepository.findSettledByInvestorAndPeriod(entityId, yearStart, yearEnd);
        List<IncomeLine> incomeLines = aggregateByAsset(entries);

        log.info("Generating Ertragsaufstellung for entity={} taxYear={} incomeLines={}", entityId, taxYear, incomeLines.size());

        try {
            byte[] unsigned = buildPdf(entity, incomeLines, taxYear);
            return signingService.isConfigured()
                    ? signingService.signPdf(unsigned, "Ertragsaufstellung " + taxYear + " — " + entity.getEntityNumber())
                    : unsigned;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Ertragsaufstellung PDF", e);
        }
    }

    private List<IncomeLine> aggregateByAsset(List<CorporateActionEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<UUID> corporateActionIds = entries.stream().map(CorporateActionEntry::getCorporateActionId).distinct().toList();
        Map<UUID, CorporateAction> actionById = corporateActionRepository.findAllById(corporateActionIds).stream()
                .collect(Collectors.toMap(CorporateAction::getId, Function.identity()));

        record Key(UUID assetId, String currency) {}
        Map<Key, BigDecimal> grossByKey = new LinkedHashMap<>();
        for (CorporateActionEntry entry : entries) {
            CorporateAction action = actionById.get(entry.getCorporateActionId());
            if (action == null || action.getAssetId() == null || entry.getEntitlementAmount() == null
                    || !INCOME_TYPES.contains(action.getActionType())) {
                continue;
            }
            String currency = action.getCurrency() != null && !action.getCurrency().isBlank()
                    ? action.getCurrency().toUpperCase(java.util.Locale.ROOT) : "?";
            grossByKey.merge(new Key(action.getAssetId(), currency), entry.getEntitlementAmount(), BigDecimal::add);
        }

        java.util.Set<UUID> assetIds = grossByKey.keySet().stream().map(Key::assetId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<UUID, Asset> assetById = assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

        // The statement spans multiple assets, so GatedOperation#TAX_CERTIFICATE_ISSUE is
        // checked per distinct assetId rather than once for the whole document — there is
        // nothing meaningful a single call could pass.
        for (UUID assetId : assetIds) {
            TokenStandard standard = assetById.containsKey(assetId) ? assetById.get(assetId).getTokenStandard() : null;
            finalityGate.require(GatedOperation.TAX_CERTIFICATE_ISSUE, assetId, standard, FinalityLevel.FINALIZED);
        }

        return grossByKey.entrySet().stream()
                .map(e -> new IncomeLine(assetById.get(e.getKey().assetId()), e.getKey().currency(), e.getValue()))
                .toList();
    }

    private byte[] buildPdf(LegalEntity entity, List<IncomeLine> incomeLines, int taxYear) throws IOException {
        Map<String, BigDecimal> totalsByCurrency = new java.util.TreeMap<>();
        for (IncomeLine line : incomeLines) {
            totalsByCurrency.merge(line.currency(), line.grossIncome(), BigDecimal::add);
        }

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPageContentStream content = new PDPageContentStream(doc, page);
            try {
                float margin = 50;
                float y = PDRectangle.A4.getHeight() - margin;

                // Header
                PdfHelper.writeText(content, margin, y, fontBold, 16, "Ertragsaufstellung");
                y -= 16;
                PdfHelper.writeText(content, margin, y, fontBold, 10,
                    "Keine Steuerbescheinigung i.S.d. § 45a EStG - nur zur Information");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontRegular, 10,
                    "Erträge aus elektronischen Wertpapieren - Kalenderjahr " + taxYear);
                y -= 8;
                PdfHelper.writeText(content, margin, y, fontRegular, 9,
                    "Ausgestellt am: " + registerClock.today().format(DATE_FMT));
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
                    "Im Kalenderjahr abgerechnete Erträge (Kupon / Zinsen / Dividenden), brutto:");
                y -= 5;
                content.moveTo(margin, y);
                content.lineTo(PDRectangle.A4.getWidth() - margin, y);
                content.stroke();
                y -= 14;

                float[] cols = {margin, 260, 400};
                PdfHelper.writeText(content, cols[0], y, fontBold, 9, "Wertpapier / ISIN");
                PdfHelper.writeText(content, cols[1], y, fontBold, 9, "Erträge (brutto)");
                PdfHelper.writeText(content, cols[2], y, fontBold, 9, "Quelle");
                y -= 12;

                if (incomeLines.isEmpty()) {
                    PdfHelper.writeText(content, cols[0], y, fontRegular, 8,
                            "Keine im Veranlagungszeitraum " + taxYear + " abgerechneten Kapitalerträge.");
                    y -= 12;
                } else {
                    for (IncomeLine line : incomeLines) {
                        if (y < margin + 40) { // paginate: never drop lines that the printed total includes
                            content.close();
                            content = new PDPageContentStream(doc, newPage(doc));
                            y = PDRectangle.A4.getHeight() - margin;
                            PdfHelper.writeText(content, cols[0], y, fontBold, 9,
                                    "Ertragsaufstellung " + taxYear + " - Fortsetzung");
                            y -= 16;
                        }
                        String assetDesc = line.asset() != null
                            ? (line.asset().getIsin() != null ? line.asset().getIsin() : line.asset().getName())
                            : "(Asset gelöscht)";
                        PdfHelper.writeText(content, cols[0], y, fontRegular, 8, PdfHelper.truncate(assetDesc, 32));
                        PdfHelper.writeText(content, cols[1], y, fontRegular, 8, line.grossIncome().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + line.currency());
                        PdfHelper.writeText(content, cols[2], y, fontRegular, 8, "Coupon-Service (settled)");
                        y -= 12;
                    }
                }

                // Tax summary
                y -= 10;
                content.moveTo(margin, y);
                content.lineTo(PDRectangle.A4.getWidth() - margin, y);
                content.stroke();
                y -= 14;
                for (Map.Entry<String, BigDecimal> total : totalsByCurrency.entrySet()) {
                    if (y < margin + 40) {
                        content.close();
                        content = new PDPageContentStream(doc, newPage(doc));
                        y = PDRectangle.A4.getHeight() - margin;
                    }
                    PdfHelper.writeText(content, margin, y, fontBold, 10,
                            "Summe Erträge " + total.getKey() + ": "
                                    + total.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + total.getKey());
                    y -= 14;
                }
                if (y < margin + 100) { // keep the closing notes together above the footer
                    content.close();
                    content = new PDPageContentStream(doc, newPage(doc));
                    y = PDRectangle.A4.getHeight() - margin;
                }
                PdfHelper.writeText(content, margin, y, fontBold, 9,
                        "Kapitalertragsteuer/SolZ einbehalten: 0,00 - Erträge wurden brutto ausgezahlt.");
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontRegular, 8,
                        "Rückzahlungen/Kündigungen (Kapital) sind nicht enthalten; Veräußerungs-/Einlösungsgewinne");
                y -= 10;
                PdfHelper.writeText(content, margin, y, fontRegular, 8,
                        "werden nicht ermittelt (Anschaffungskosten nicht im Register).");
                y -= 10;
                PdfHelper.writeText(content, margin, y, fontRegular, 8,
                        "Beträge je Währung ausgewiesen, keine Umrechnung. Kirchensteuer wird nicht erfasst.");
                y -= 20;

                // Footer
                String signatureClaim = signingService.isConfigured()
                        ? "Dieses Dokument wird digital signiert (PAdES-B-B, CMS/PKCS#7)."
                        : "Dieses Dokument ist NICHT digital signiert (kein Signaturzertifikat konfiguriert).";
                PdfHelper.writeText(content, margin, margin + 20, fontRegular, 7,
                    "Diese Ertragsaufstellung wurde maschinell erstellt. Sie ist keine Steuerbescheinigung und " +
                    "ersetzt keine Bescheinigung einer auszahlenden Stelle. " + signatureClaim + " Registerwerk eWpG-Registry.");
            } finally {
                content.close();
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static PDPage newPage(PDDocument doc) {
        PDPage next = new PDPage(PDRectangle.A4);
        doc.addPage(next);
        return next;
    }
}
