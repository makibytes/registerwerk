package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.RegisterReconciliationGuard;
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

        // 9A-05: a position of a register that was handed to a successor (TRANSFERRED_OUT) is not a current position
        // here; it is listed separately at the end instead of among the holdings.
        List<AssetHolder> administered = holdings.stream()
                .filter(h -> isAdministeredHere(assetById.get(h.getAssetId()))).toList();
        List<AssetHolder> handedOver = holdings.stream()
                .filter(h -> !isAdministeredHere(assetById.get(h.getAssetId()))).toList();

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
            y -= 12;
            PdfHelper.writeText(content, MARGIN, y, fontRegular, 10, registerStandLine(administered, assetById));
            y -= 20;

            PdfHelper.writeText(content, MARGIN, y, fontBold, 11, "Bestände / Holdings:");
            y -= 15;

            y = writeTableHeader(content, y, fontBold, pageWidth);

            for (AssetHolder holder : administered) {
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
                boolean unconfirmed = RegisterReconciliationGuard.isBlocked(asset);
                // 9A-05 (T9-01): a BLOCKED holder sync wrote no holder row, so the nominal may be stale: never print it.
                String nominal = unconfirmed ? "unconfirmed"
                        : holder.getNominalAmount() != null ? holder.getNominalAmount().toPlainString() : "0";
                String wallet = holder.getWalletAddress() != null
                        ? holder.getWalletAddress().substring(0, Math.min(16, holder.getWalletAddress().length())) + "…"
                        : "—";

                PdfHelper.writeText(content, COL_X[0], y, fontRegular, 9, PdfHelper.truncate(assetName, 25));
                PdfHelper.writeText(content, COL_X[1], y, fontRegular, 9, isin);
                PdfHelper.writeText(content, COL_X[2], y, fontRegular, 9, nominal);
                PdfHelper.writeText(content, COL_X[3], y, fontRegular, 9, wallet);
                y -= 14;
                String note = rowNote(asset);
                if (note != null) {
                    PdfHelper.writeText(content, COL_X[0] + 8, y + 2, fontRegular, 7, note);
                    y -= 9;
                }
            }

            if (!handedOver.isEmpty()) {
                y -= 10;
                PdfHelper.writeText(content, MARGIN, y, fontBold, 10,
                        "Nicht mehr hier verwaltet / No longer administered here");
                y -= 11;
                PdfHelper.writeText(content, MARGIN, y, fontRegular, 8,
                        "(the register was handed over to a successor registrar; no current position is shown)");
                y -= 14;
                for (AssetHolder holder : handedOver) {
                    if (y < MARGIN + 50) {
                        writeFooter(content, fontRegular, entity, signingService);
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        content = new PDPageContentStream(doc, page);
                        y = PDRectangle.A4.getHeight() - MARGIN;
                    }
                    Asset asset = assetById.get(holder.getAssetId());
                    PdfHelper.writeText(content, COL_X[0], y, fontRegular, 9,
                            PdfHelper.truncate(asset != null ? asset.getName() : holder.getAssetId().toString(), 25));
                    PdfHelper.writeText(content, COL_X[1], y, fontRegular, 9,
                            asset != null && asset.getIsin() != null ? asset.getIsin() : "—");
                    y -= 14;
                }
            }

            if (administered.stream().anyMatch(h -> RegisterReconciliationGuard.isBlocked(assetById.get(h.getAssetId())))) {
                y -= 6;
                PdfHelper.writeText(content, MARGIN, y, fontRegular, 8,
                        "nicht bestätigt / unconfirmed: the register of this security is being reconciled with the chain; the nominal is withheld.");
            }

            writeFooter(content, fontRegular, entity, signingService);
            content.close();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static boolean isAdministeredHere(Asset asset) {
        return asset == null || asset.getStatus() == null || asset.getStatus().isAdministeredHere();
    }

    /** Extra line under a row whose position is not simply "held and ISSUED" (9A-05); null when nothing to add. */
    private static String rowNote(Asset asset) {
        if (asset == null) {
            return null;
        }
        if (RegisterReconciliationGuard.isBlocked(asset)) {
            return "nicht bestätigt / unconfirmed — register reconciliation pending";
        }
        AssetStatus status = asset.getStatus();
        if (status == AssetStatus.REDEEMED || status == AssetStatus.REDEMPTION_PENDING) {
            return "Status: " + status;
        }
        return null;
    }

    /** "Stand / as of": the OLDEST successful reconciliation among the listed positions' registers. */
    private static String registerStandLine(List<AssetHolder> listed, Map<UUID, Asset> assetById) {
        java.time.Instant oldest = null;
        for (AssetHolder h : listed) {
            Asset a = assetById.get(h.getAssetId());
            java.time.Instant at = a == null ? null : a.getLastSuccessfulHolderSyncAt();
            if (at != null && (oldest == null || at.isBefore(oldest))) {
                oldest = at;
            }
        }
        String ts = oldest == null ? null : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(oldest);
        if (ts == null) {
            return "Registerstand / Register status: no on-chain reconciliation recorded";
        }
        return "Registerstand / Register status: oldest on-chain reconciliation " + ts;
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
