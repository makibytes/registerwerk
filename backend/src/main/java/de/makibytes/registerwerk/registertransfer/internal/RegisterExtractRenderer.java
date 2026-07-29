package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a §10 eWpG register inspection extract as a PDF.
 *
 * <p>The extract shows the register content for an asset: the security's data
 * and the list of holders. In single entry, holders are shown by their
 * pseudonymous reference (§17(2)); the clear-name investor link is never
 * disclosed in the extract. A non-beneficiary applicant only sees the security
 * data and aggregate holder positions, not third-party rights or disposal
 * restrictions, which §10 eWpRV reserves to the parties they concern.
 */
@Component
class RegisterExtractRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DocumentSigningService signingService;

    RegisterExtractRenderer(DocumentSigningService signingService) {
        this.signingService = signingService;
    }

    byte[] render(Asset asset, List<AssetHolder> holders,
                  InspectionLegalBasis basis, String requesterName) {
        boolean discloseDetail = basis == InspectionLegalBasis.ISSUER
                || basis == InspectionLegalBasis.HOLDER
                || basis == InspectionLegalBasis.BENEFICIARY;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            float margin = 50;
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float y = pageHeight - margin;
            float[] colX = {margin, 220, 360};

            PDPageContentStream c = new PDPageContentStream(doc, page);
            write(c, margin, y, fontBold, 18, "Registereinsicht / Register inspection");
            y -= 18;
            write(c, margin, y, fontRegular, 9, "Extract pursuant to § 10 eWpG");
            y -= 26;

            write(c, margin, y, fontRegular, 10,
                    "Ausgestellt am / Issued: " + LocalDate.now().format(DATE_FMT));
            y -= 13;
            write(c, margin, y, fontRegular, 10, "Einsichtnehmer / Inspector: " + safe(requesterName));
            y -= 13;
            write(c, margin, y, fontRegular, 10, "Grundlage / Basis: " + basis);
            y -= 24;

            write(c, margin, y, fontBold, 12, "Wertpapier / Security");
            y -= 16;
            write(c, margin, y, fontRegular, 10, "Bezeichnung / Name: " + safe(asset.getName()));
            y -= 13;
            write(c, margin, y, fontRegular, 10,
                    "ISIN: " + (asset.getIsin() != null ? asset.getIsin() : "—"));
            y -= 13;
            write(c, margin, y, fontRegular, 10,
                    "Eintragungsart / Entry type: " + asset.getEntryType());
            y -= 24;

            write(c, margin, y, fontBold, 12, "Inhaberbestand / Holdings");
            y -= 16;
            y = writeHoldingsHeader(c, y, fontBold, margin, pageWidth, colX, discloseDetail);

            // §10 must disclose every holder of record — a fixed single page would silently
            // truncate the extract past ~70 holders, so this mirrors PositionStatementService's
            // page-continuation pattern instead.
            for (AssetHolder h : holders) {
                if (y < margin + 50) {
                    c.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = pageHeight - margin;
                    write(c, margin, y, fontBold, 11, "Inhaberbestand / Holdings (Fortsetzung / continued)");
                    y -= 16;
                    y = writeHoldingsHeader(c, y, fontBold, margin, pageWidth, colX, discloseDetail);
                }

                String ref = h.getHolderReference() != null
                        ? h.getHolderReference()
                        : "(Sammeleintragung)";
                String nominal = h.getNominalAmount() != null ? h.getNominalAmount().toPlainString() : "0";
                write(c, colX[0], y, fontRegular, 9, ref);
                write(c, colX[1], y, fontRegular, 9, nominal);
                if (discloseDetail && h.getDisposalRestrictions() != null) {
                    write(c, colX[2], y, fontRegular, 8, truncate(h.getDisposalRestrictions(), 30));
                }
                y -= 13;
            }

            String signatureClaim = signingService.isConfigured()
                    ? "Dieses Dokument wird digital signiert (PAdES-B-B, CMS/PKCS#7)."
                    : "Dieses Dokument ist NICHT digital signiert (kein Signaturzertifikat konfiguriert).";
            float footerY = margin + 20;
            write(c, margin, footerY, fontRegular, 8,
                    "Dieser Auszug spiegelt den Registerinhalt zum Ausstellungszeitpunkt (§10 eWpG). "
                            + signatureClaim);
            c.close();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            byte[] unsigned = bos.toByteArray();
            return signingService.isConfigured()
                    ? signingService.signPdf(unsigned, "Registereinsicht — " + safe(asset.getIsin()))
                    : unsigned;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render register extract PDF", e);
        }
    }

    private static float writeHoldingsHeader(PDPageContentStream c, float y, PDType1Font fontBold,
                                             float margin, float pageWidth, float[] colX, boolean discloseDetail)
            throws IOException {
        write(c, colX[0], y, fontBold, 9, "Kennung / Reference");
        write(c, colX[1], y, fontBold, 9, "Nennbetrag / Nominal");
        if (discloseDetail) {
            write(c, colX[2], y, fontBold, 9, "Beschränkungen / Restrictions");
        }
        y -= 5;
        c.moveTo(margin, y);
        c.lineTo(pageWidth - margin, y);
        c.stroke();
        return y - 12;
    }

    private static void write(PDPageContentStream c, float x, float y,
                              PDType1Font font, float size, String text) throws IOException {
        c.setFont(font, size);
        c.beginText();
        c.newLineAtOffset(x, y);
        c.showText(text != null ? text : "");
        c.endText();
    }

    private static String safe(String s) {
        return s != null && !s.isBlank() ? s : "—";
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 1) + "…" : (s != null ? s : "");
    }
}
