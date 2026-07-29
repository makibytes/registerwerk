package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
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
 * Generates investor position statements (Depotauszug) as PDFs, PAdES-B-B signed when
 * {@code registerwerk.docsig.*} is configured (see {@link DocumentSigningService}).
 * Uses Apache PDFBox 3.x.
 * Endpoint: GET /api/v1/me/statements (customer), GET /api/v1/customers/{id}/statements (operator)
 */
@Service
public class PositionStatementService {

    private static final Logger log = LoggerFactory.getLogger(PositionStatementService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final DocumentSigningService signingService;

    PositionStatementService(AssetHolderRepository holderRepository,
                              AssetRepository assetRepository,
                              LegalEntityRepository entityRepository,
                              DocumentSigningService signingService) {
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.signingService = signingService;
    }

    @Transactional(readOnly = true)
    public byte[] generateForEntity(UUID entityId) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        // Depotauszug reflects the investor's current holdings; a removed holder record is not one.
        List<AssetHolder> holdings = holderRepository.findActiveByInvestorId(entityId);

        log.info("Generating position statement for entity={}, holdings={}", entityId, holdings.size());

        try {
            byte[] unsigned = buildPdf(entity, holdings);
            return signingService.isConfigured()
                    ? signingService.signPdf(unsigned, "Depotauszug — " + entity.getEntityNumber())
                    : unsigned;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate position statement PDF", e);
        }
    }

    private static final float MARGIN = 50;
    private static final float[] COL_X = {MARGIN, 180, 320, 420};

    private byte[] buildPdf(LegalEntity entity, List<AssetHolder> holdings) throws IOException {
        List<UUID> assetIds = holdings.stream().map(AssetHolder::getAssetId).toList();
        Map<UUID, Asset> assetById = assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float pageWidth = PDRectangle.A4.getWidth();

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream content = new PDPageContentStream(doc, page);
            float y = PDRectangle.A4.getHeight() - MARGIN;

            PdfHelper.writeText(content, MARGIN, y, fontBold, 18, "Depotauszug / Position Statement");
            y -= 25;

            PdfHelper.writeText(content, MARGIN, y, fontRegular, 10, "Datum / Date: " + LocalDate.now().format(DATE_FMT));
            y -= 15;

            y -= 10;
            PdfHelper.writeText(content, MARGIN, y, fontBold, 12, "Inhaber / Holder:");
            y -= 15;
            PdfHelper.writeText(content, MARGIN, y, fontRegular, 10, "Registrierungsnummer: " + entity.getEntityNumber());
            y -= 12;
            PdfHelper.writeText(content, MARGIN, y, fontRegular, 10, "KYC-Status: " + entity.getKycStatus());
            y -= 20;

            PdfHelper.writeText(content, MARGIN, y, fontBold, 11, "Bestände / Holdings:");
            y -= 15;

            y = writeTableHeader(content, y, fontBold, pageWidth);

            for (AssetHolder holder : holdings) {
                if (y < MARGIN + 50) {
                    // A Depotauszug must list every position (eWpG §19); overflow continues
                    // on a fresh page instead of silently truncating the register extract.
                    writeFooter(content, fontRegular, entity, signingService);
                    content.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    content = new PDPageContentStream(doc, page);
                    y = PDRectangle.A4.getHeight() - MARGIN;
                    PdfHelper.writeText(content, MARGIN, y, fontBold, 11,
                            "Bestände / Holdings (Fortsetzung / continued):");
                    y -= 15;
                    y = writeTableHeader(content, y, fontBold, pageWidth);
                }

                Asset asset = assetById.get(holder.getAssetId());
                String assetName = asset != null ? asset.getName() : holder.getAssetId().toString();
                String isin = asset != null && asset.getIsin() != null ? asset.getIsin() : "—";
                String nominal = holder.getNominalAmount() != null ? holder.getNominalAmount().toPlainString() : "0";
                String wallet = holder.getWalletAddress() != null
                        ? holder.getWalletAddress().substring(0, Math.min(16, holder.getWalletAddress().length())) + "…"
                        : "—";

                PdfHelper.writeText(content, COL_X[0], y, fontRegular, 9, PdfHelper.truncate(assetName, 25));
                PdfHelper.writeText(content, COL_X[1], y, fontRegular, 9, isin);
                PdfHelper.writeText(content, COL_X[2], y, fontRegular, 9, nominal);
                PdfHelper.writeText(content, COL_X[3], y, fontRegular, 9, wallet);
                y -= 14;
            }

            writeFooter(content, fontRegular, entity, signingService);
            content.close();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static float writeTableHeader(PDPageContentStream content, float y,
                                          PDType1Font fontBold, float pageWidth) throws IOException {
        PdfHelper.writeText(content, COL_X[0], y, fontBold, 9, "Asset / Wertpapier");
        PdfHelper.writeText(content, COL_X[1], y, fontBold, 9, "ISIN");
        PdfHelper.writeText(content, COL_X[2], y, fontBold, 9, "Nennbetrag");
        PdfHelper.writeText(content, COL_X[3], y, fontBold, 9, "Wallet-Adresse");
        y -= 5;
        content.moveTo(MARGIN, y);
        content.lineTo(pageWidth - MARGIN, y);
        content.stroke();
        return y - 12;
    }

    private static void writeFooter(PDPageContentStream content, PDType1Font fontRegular,
                                    LegalEntity entity, DocumentSigningService signingService) throws IOException {
        String signatureClaim = signingService.isConfigured()
                ? "Dieses Dokument wird digital signiert (PAdES-B-B, CMS/PKCS#7)."
                : "Dieses Dokument ist NICHT digital signiert (kein Signaturzertifikat konfiguriert).";
        PdfHelper.writeText(content, MARGIN, MARGIN + 20, fontRegular, 8,
                "Dieses Dokument wurde elektronisch erstellt. " + signatureClaim + " " +
                "Registerwerk eWpG-Registry — " + entity.getRegistrationCountry());
    }

}
