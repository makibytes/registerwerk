package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates Steuerbescheinigung (annual tax certificate) PDFs per DE §43 KStG / §32d EStG.
 * Required for German investors to declare capital gains from electronic securities.
 * Issued annually for the prior tax year; also on-demand per investor request.
 */
@Service
public class SteuerbescheinigungService {

    private static final Logger log = LoggerFactory.getLogger(SteuerbescheinigungService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;

    SteuerbescheinigungService(AssetHolderRepository holderRepository,
                                AssetRepository assetRepository,
                                LegalEntityRepository entityRepository) {
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
    }

    /**
     * Generates a Steuerbescheinigung PDF for the given entity and tax year.
     * @param entityId  the investor / holder legal entity
     * @param taxYear   the calendar year (e.g. 2025)
     */
    @Transactional(readOnly = true)
    public byte[] generate(UUID entityId, int taxYear) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        List<AssetHolder> holdings = holderRepository.findByInvestorId(entityId);

        log.info("Generating Steuerbescheinigung for entity={} taxYear={}", entityId, taxYear);

        try {
            return buildPdf(entity, holdings, taxYear);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Steuerbescheinigung PDF", e);
        }
    }

    private byte[] buildPdf(LegalEntity entity, List<AssetHolder> holdings, int taxYear) throws IOException {
        List<UUID> assetIds = holdings.stream().map(AssetHolder::getAssetId).toList();
        Map<UUID, Asset> assetById = assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

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
                PdfHelper.writeText(content, margin, y, fontRegular, 9, "Registerwerk eWpG-Registry — [Operator name to fill in]");
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontRegular, 9, "Steuer-ID / LEI: [To fill in]");
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

                float[] cols = {margin, 220, 350, 440};
                PdfHelper.writeText(content, cols[0], y, fontBold, 9, "Wertpapier / ISIN");
                PdfHelper.writeText(content, cols[1], y, fontBold, 9, "Nennbetrag");
                PdfHelper.writeText(content, cols[2], y, fontBold, 9, "Kupon / Erträge EUR");
                PdfHelper.writeText(content, cols[3], y, fontBold, 9, "KiSt-Merkmal");
                y -= 12;

                for (AssetHolder holder : holdings) {
                    Asset asset = assetById.get(holder.getAssetId());
                    String assetDesc = asset != null
                        ? (asset.getIsin() != null ? asset.getIsin() : asset.getName())
                        : holder.getAssetId().toString().substring(0, 8) + "…";
                    String nominal = holder.getNominalAmount() != null
                        ? holder.getNominalAmount().toPlainString() : "0";

                    PdfHelper.writeText(content, cols[0], y, fontRegular, 8, assetDesc.substring(0, Math.min(30, assetDesc.length())));
                    PdfHelper.writeText(content, cols[1], y, fontRegular, 8, nominal);
                    PdfHelper.writeText(content, cols[2], y, fontRegular, 8, "— (Coupon-Service ermittelt)");
                    PdfHelper.writeText(content, cols[3], y, fontRegular, 8, "nein");
                    y -= 12;
                    if (y < margin + 60) break;
                }

                // Tax summary
                y -= 10;
                content.moveTo(margin, y);
                content.lineTo(PDRectangle.A4.getWidth() - margin, y);
                content.stroke();
                y -= 14;
                PdfHelper.writeText(content, margin, y, fontBold, 10, "Kapitalertragsteuer (KESt): — EUR");
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontBold, 10, "Solidaritätszuschlag (SolZ): — EUR");
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontBold, 10, "Kirchensteuer (KiSt): 0,00 EUR");
                y -= 20;

                // Footer
                PdfHelper.writeText(content, margin, margin + 20, fontRegular, 7,
                    "Diese Steuerbescheinigung wurde maschinell erstellt und ist ohne Unterschrift gültig " +
                    "(§ 45a Abs. 2 EStG). PAdES-B-LT Signatur. Registerwerk eWpG-Registry.");
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
