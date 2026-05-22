package de.makibytes.registerwerk.corporateactions.internal;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.IOException;

/**
 * Shared PDF rendering helpers for PositionStatementService and SteuerbescheinigungService.
 */
final class PdfHelper {

    private PdfHelper() {}

    static void writeText(PDPageContentStream content, float x, float y,
                          PDType1Font font, float size, String text) throws IOException {
        content.setFont(font, size);
        content.beginText();
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 1) + "…" : (s != null ? s : "");
    }
}
