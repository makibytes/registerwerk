package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a §19 eWpG register statement (Registerauszug) as a PDF in text form.
 *
 * <p>Content reflects the single-entry register data the holder is entitled to
 * see (§17 eWpG): the security, its ISIN, the holder's pseudonymous reference,
 * the nominal amount held, and — where recorded — third-party rights, disposal
 * restrictions and legal-capacity notes (§17(2)). The statement also names the
 * trigger event and the issuance timestamp so the document is self-describing.
 */
final class RegisterStatementPdfRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private RegisterStatementPdfRenderer() {}

    static byte[] render(Asset asset, AssetHolder holder, LegalEntity investor,
                         StatementTrigger trigger, String registryName, String registryCountry) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            float margin = 50;
            float pageWidth = page.getMediaBox().getWidth();
            float y = page.getMediaBox().getHeight() - margin;

            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                write(c, margin, y, fontBold, 18, "Registerauszug");
                y -= 18;
                write(c, margin, y, fontRegular, 9,
                        "Register statement pursuant to § 19 eWpG (electronic securities act)");
                y -= 28;

                write(c, margin, y, fontRegular, 10,
                        "Ausstellungsdatum / Issued: " + LocalDate.now().format(DATE_FMT));
                y -= 14;
                write(c, margin, y, fontRegular, 10, "Anlass / Trigger: " + triggerLabel(trigger));
                y -= 14;
                write(c, margin, y, fontRegular, 10,
                        "Registerführende Stelle / Registry operator: " + safe(registryName));
                y -= 24;

                // ── Holder block (pseudonymous in single entry) ───────────────
                write(c, margin, y, fontBold, 12, "Inhaber / Holder");
                y -= 16;
                write(c, margin, y, fontRegular, 10,
                        "Kennung / Reference: " + safe(holder.getHolderReference()));
                y -= 13;
                write(c, margin, y, fontRegular, 10,
                        "Registrierungsnummer / Registration no.: " + safe(investor.getEntityNumber()));
                y -= 24;

                // ── Security block ────────────────────────────────────────────
                write(c, margin, y, fontBold, 12, "Wertpapier / Security");
                y -= 16;
                write(c, margin, y, fontRegular, 10, "Bezeichnung / Name: " + safe(asset.getName()));
                y -= 13;
                write(c, margin, y, fontRegular, 10,
                        "ISIN: " + (asset.getIsin() != null ? asset.getIsin() : "—"));
                y -= 13;
                write(c, margin, y, fontRegular, 10,
                        "Eintragungsart / Entry type: " + asset.getEntryType());
                y -= 13;
                write(c, margin, y, fontRegular, 10,
                        "Nennbetrag / Nominal amount: "
                                + (holder.getNominalAmount() != null
                                        ? holder.getNominalAmount().toPlainString() : "0"));
                y -= 13;
                write(c, margin, y, fontRegular, 10,
                        "Netzwerk-Adresse / Wallet: " + safe(holder.getWalletAddress()));
                y -= 24;

                // ── §17(2) additional single-entry content, when present ──────
                if (hasText(holder.getThirdPartyRights())
                        || hasText(holder.getDisposalRestrictions())
                        || hasText(holder.getLegalCapacityNote())) {
                    write(c, margin, y, fontBold, 12, "Weitere Angaben / Additional entries (§17(2) eWpG)");
                    y -= 16;
                    if (hasText(holder.getThirdPartyRights())) {
                        write(c, margin, y, fontRegular, 10,
                                "Rechte Dritter / Third-party rights: " + holder.getThirdPartyRights());
                        y -= 13;
                    }
                    if (hasText(holder.getDisposalRestrictions())) {
                        write(c, margin, y, fontRegular, 10,
                                "Verfügungsbeschränkungen / Disposal restrictions: "
                                        + holder.getDisposalRestrictions());
                        y -= 13;
                    }
                    if (hasText(holder.getLegalCapacityNote())) {
                        write(c, margin, y, fontRegular, 10,
                                "Geschäftsfähigkeit / Legal capacity: " + holder.getLegalCapacityNote());
                        y -= 13;
                    }
                    y -= 11;
                }

                // ── Footer ────────────────────────────────────────────────────
                float footerY = margin + 24;
                c.moveTo(margin, footerY + 12);
                c.lineTo(pageWidth - margin, footerY + 12);
                c.stroke();
                write(c, margin, footerY, fontRegular, 8,
                        "Dieses Dokument wurde elektronisch in Textform erstellt (§19 eWpG). "
                                + "Registerwerk eWpG-Registry — " + safe(registryCountry));
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render register statement PDF", e);
        }
    }

    private static String triggerLabel(StatementTrigger trigger) {
        return switch (trigger) {
            case INITIAL_ENTRY -> "Ersteintragung / Initial entry (§19(2) Nr. 1)";
            case CHANGE -> "Änderung des Registerinhalts / Register change (§19(2) Nr. 2)";
            case ANNUAL -> "Jährlicher Auszug / Annual statement (§19(2) Nr. 3)";
            case ON_DEMAND -> "Auf Verlangen / On demand (§19(1))";
        };
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
