package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Renders a trade confirmation (Wertpapierabrechnung / contract note) for a SETTLED
 * {@link TradeExecution} — previously nothing consumed {@code TradeExecutedEvent} and neither
 * counterparty had any document evidencing what they bought/sold, at what price, and when.
 * Generated on demand at download time, same pattern as {@code RegisterStatementPdfRenderer} —
 * nothing is pre-rendered or cached.
 */
final class TradeConfirmationPdfRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'");
    private static final float MARGIN = 50;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();

    private TradeConfirmationPdfRenderer() {}

    static byte[] render(TradeExecution execution, LegalEntity buyer, LegalEntity seller) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream c = new PDPageContentStream(doc, page);
            float y = PAGE_HEIGHT - MARGIN;

            write(c, MARGIN, y, fontBold, 18, "Wertpapierabrechnung / Trade Confirmation");
            y -= 18;
            write(c, MARGIN, y, fontRegular, 9, "Handels-ID / Trade ID: " + execution.getId());
            y -= 28;

            write(c, MARGIN, y, fontRegular, 10, "Abgerechnet am / Settled: " + instant(execution.getSettledAt()));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Ausgeführt am / Executed: " + instant(execution.getCreatedAt()));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Handelsplatz / Venue: " + execution.getVenueCode());
            y -= 24;

            write(c, MARGIN, y, fontBold, 12, "Käufer / Buyer");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10, entityLabel(buyer));
            y -= 24;

            write(c, MARGIN, y, fontBold, 12, "Verkäufer / Seller");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10, entityLabel(seller));
            y -= 24;

            write(c, MARGIN, y, fontBold, 12, "Wertpapier / Security");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10, "Bezeichnung / Name: " + safe(execution.getAssetName()));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "ISIN: " + (execution.getIsin() != null ? execution.getIsin() : "—"));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Token-Standard / Token standard: " + execution.getTokenStandard());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Chain: " + (execution.getChain() != null ? execution.getChain() : "—"));
            y -= 24;

            write(c, MARGIN, y, fontBold, 12, "Ausführung / Execution");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10, "Ordertyp / Order type: " + execution.getOrderType());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10,
                    "Menge / Quantity: " + execution.getExecutedQuantity().toPlainString());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Preis je Einheit / Unit price: " + execution.getUnitPrice().toPlainString());
            y -= 13;
            write(c, MARGIN, y, fontBold, 11, "Gesamtbetrag / Total: " + execution.getTotalPrice().toPlainString());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Zahlungsart / Payment option: " + execution.getPaymentOption());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Zahlungsreferenz / Payment reference: " + safe(execution.getPaymentReference()));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Empfänger-Wallet / Settlement wallet: " + safe(execution.getWalletAddress()));
            y -= 24;

            float footerY = MARGIN + 24;
            c.moveTo(MARGIN, footerY + 12);
            c.lineTo(PAGE_WIDTH - MARGIN, footerY + 12);
            c.stroke();
            write(c, MARGIN, footerY, fontRegular, 8,
                    "Dieses Dokument wurde elektronisch in Textform erstellt und dient der "
                            + "Information beider Vertragsparteien über die abgerechnete Transaktion. "
                            + "This document was generated electronically and confirms the settled trade above.");
            c.close();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render trade confirmation PDF", e);
        }
    }

    private static String entityLabel(LegalEntity entity) {
        if (entity == null) return "—";
        return safe(entity.getCurrentName()) + " (" + safe(entity.getEntityNumber()) + ")";
    }

    private static String instant(Instant instant) {
        return instant != null ? DATE_FMT.format(instant.atZone(ZoneOffset.UTC)) : "—";
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
}
