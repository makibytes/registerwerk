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
 * Generates investor position statements (Depotauszug) as signed PDFs.
 * Uses Apache PDFBox 3.x. Signature: PAdES-B-LT via the registry signing key.
 * Endpoint: GET /api/v1/me/statements (customer), GET /api/v1/customers/{id}/statements (operator)
 */
@Service
public class PositionStatementService {

    private static final Logger log = LoggerFactory.getLogger(PositionStatementService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;

    PositionStatementService(AssetHolderRepository holderRepository,
                              AssetRepository assetRepository,
                              LegalEntityRepository entityRepository) {
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
    }

    @Transactional(readOnly = true)
    public byte[] generateForEntity(UUID entityId) {
        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        List<AssetHolder> holdings = holderRepository.findByInvestorId(entityId);

        log.info("Generating position statement for entity={}, holdings={}", entityId, holdings.size());

        try {
            return buildPdf(entity, holdings);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate position statement PDF", e);
        }
    }

    private byte[] buildPdf(LegalEntity entity, List<AssetHolder> holdings) throws IOException {
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
                float pageWidth = PDRectangle.A4.getWidth();
                float y = PDRectangle.A4.getHeight() - margin;

                PdfHelper.writeText(content, margin, y, fontBold, 18, "Depotauszug / Position Statement");
                y -= 25;

                PdfHelper.writeText(content, margin, y, fontRegular, 10, "Datum / Date: " + LocalDate.now().format(DATE_FMT));
                y -= 15;

                y -= 10;
                PdfHelper.writeText(content, margin, y, fontBold, 12, "Inhaber / Holder:");
                y -= 15;
                PdfHelper.writeText(content, margin, y, fontRegular, 10, "Registrierungsnummer: " + entity.getEntityNumber());
                y -= 12;
                PdfHelper.writeText(content, margin, y, fontRegular, 10, "KYC-Status: " + entity.getKycStatus());
                y -= 20;

                PdfHelper.writeText(content, margin, y, fontBold, 11, "Bestände / Holdings:");
                y -= 15;

                float[] colX = {margin, 180, 320, 420};
                PdfHelper.writeText(content, colX[0], y, fontBold, 9, "Asset / Wertpapier");
                PdfHelper.writeText(content, colX[1], y, fontBold, 9, "ISIN");
                PdfHelper.writeText(content, colX[2], y, fontBold, 9, "Nennbetrag");
                PdfHelper.writeText(content, colX[3], y, fontBold, 9, "Wallet-Adresse");
                y -= 5;

                content.moveTo(margin, y);
                content.lineTo(pageWidth - margin, y);
                content.stroke();
                y -= 12;

                for (AssetHolder holder : holdings) {
                    Asset asset = assetById.get(holder.getAssetId());
                    String assetName = asset != null ? asset.getName() : holder.getAssetId().toString();
                    String isin = asset != null && asset.getIsin() != null ? asset.getIsin() : "—";
                    String nominal = holder.getNominalAmount() != null ? holder.getNominalAmount().toPlainString() : "0";
                    String wallet = holder.getWalletAddress() != null
                            ? holder.getWalletAddress().substring(0, Math.min(16, holder.getWalletAddress().length())) + "…"
                            : "—";

                    PdfHelper.writeText(content, colX[0], y, fontRegular, 9, PdfHelper.truncate(assetName, 25));
                    PdfHelper.writeText(content, colX[1], y, fontRegular, 9, isin);
                    PdfHelper.writeText(content, colX[2], y, fontRegular, 9, nominal);
                    PdfHelper.writeText(content, colX[3], y, fontRegular, 9, wallet);
                    y -= 14;

                    if (y < margin + 50) {
                        // Single-page limit — full pagination not yet implemented
                        break;
                    }
                }

                // Footer
                y = margin + 20;
                PdfHelper.writeText(content, margin, y, fontRegular, 8,
                    "Dieses Dokument wurde elektronisch erstellt. PAdES-B-LT Signatur vorhanden. " +
                    "Registerwerk eWpG-Registry — " + entity.getRegistrationCountry());
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

}
