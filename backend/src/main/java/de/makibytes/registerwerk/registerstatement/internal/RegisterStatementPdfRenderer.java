package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.RegisterDocumentProfile;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a register document (Registerauszug, § 19 eWpG, or a cross-jurisdiction
 * labeled analogue / holding confirmation — see {@link RegisterDocumentProfile}) as a
 * PDF in text form.
 *
 * <p>Content reflects the register data the holder is entitled to see (§17 eWpG): the
 * security, its ISIN, issuer, register type, the holder's pseudonymous reference, the
 * nominal amount held, the issuance conditions reference, and — where recorded —
 * third-party rights, disposal restrictions, legal-capacity notes and structured
 * Sperrvermerke (§17(2)). The statement also names the trigger event and the issuance
 * timestamp so the document is self-describing. Title and legal-basis wording are
 * driven entirely by the resolved {@link RegisterDocumentProfile} — never hardcoded to
 * § 19 eWpG — so a French/Luxembourg/Liechtenstein asset, or a collectively held
 * (nominee) holding, is never mislabeled as a German statutory register statement.
 */
final class RegisterStatementPdfRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final float MARGIN = 50;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();

    private RegisterStatementPdfRenderer() {}

    static byte[] render(Asset asset, AssetHolder holder, LegalEntity investor, LegalEntity issuer,
                        List<HolderBlock> blocks, AssetBondTerms bondTerms, AssetDocument termSheet,
                        StatementTrigger trigger, RegisterDocumentProfile profile,
                        String registryName, String registryCountry, LocalDate issuedDate) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream c = new PDPageContentStream(doc, page);
            float y = PAGE_HEIGHT - MARGIN;

            write(c, MARGIN, y, fontBold, 18, profile.title());
            y -= 18;
            write(c, MARGIN, y, fontRegular, 9, profile.legalBasisLine());
            y -= 28;

            write(c, MARGIN, y, fontRegular, 10, "Ausstellungsdatum / Issued: " + issuedDate.format(DATE_FMT));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Anlass / Trigger: " + triggerLabel(trigger));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10,
                    "Registerführende Stelle / Registry operator: " + safe(registryName));
            y -= 24;

            // ── Holder block (pseudonymous in single entry) ───────────────
            write(c, MARGIN, y, fontBold, 12, "Inhaber / Holder");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10,
                    "Kennung / Reference: " + safe(holder.getHolderReference()));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10,
                    "Registrierungsnummer / Registration no.: " + safe(investor.getEntityNumber()));
            y -= 24;

            // ── Security block ────────────────────────────────────────────
            write(c, MARGIN, y, fontBold, 12, "Wertpapier / Security");
            y -= 16;
            write(c, MARGIN, y, fontRegular, 10, "Bezeichnung / Name: " + safe(asset.getName()));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10,
                    "ISIN: " + (asset.getIsin() != null ? asset.getIsin() : "—"));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Emittent / Issuer: " + issuerLabel(issuer));
            y -= 13;
            // Rendered from the holder's own entry type, not the asset's — an asset in
            // Mischbestand (MIXED) can have both individual and collective holders, and
            // only the holder's own entry type is the legally relevant one for them.
            write(c, MARGIN, y, fontRegular, 10, "Eintragungsart / Entry type: " + holder.getEntryType());
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Registerart / Register type: " + registerTypeLabel(asset));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10,
                    "Nennbetrag / Nominal amount: "
                            + (holder.getNominalAmount() != null
                                    ? holder.getNominalAmount().toPlainString() : "0"));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Netzwerk-Adresse / Wallet: " + safe(holder.getWalletAddress()));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10, "Emissionsvolumen / Issuance volume: " + volumeLabel(bondTerms));
            y -= 13;
            write(c, MARGIN, y, fontRegular, 10,
                    "Emissionsbedingungen / Terms of issue (§16 eWpG): " + termSheetLabel(termSheet));
            y -= 24;

            // ── §17(2) additional single-entry content + structured Sperrvermerke ──
            boolean hasFreeText = hasText(holder.getThirdPartyRights())
                    || hasText(holder.getDisposalRestrictions())
                    || hasText(holder.getLegalCapacityNote());
            boolean hasBlocks = blocks != null && !blocks.isEmpty();
            if (hasFreeText || hasBlocks) {
                write(c, MARGIN, y, fontBold, 12, "Weitere Angaben / Additional entries (§17(2) eWpG)");
                y -= 16;
                if (hasText(holder.getThirdPartyRights())) {
                    write(c, MARGIN, y, fontRegular, 10,
                            "Rechte Dritter / Third-party rights: " + holder.getThirdPartyRights());
                    y -= 13;
                }
                if (hasText(holder.getDisposalRestrictions())) {
                    write(c, MARGIN, y, fontRegular, 10,
                            "Verfügungsbeschränkungen / Disposal restrictions: "
                                    + holder.getDisposalRestrictions());
                    y -= 13;
                }
                if (hasText(holder.getLegalCapacityNote())) {
                    write(c, MARGIN, y, fontRegular, 10,
                            "Geschäftsfähigkeit / Legal capacity: " + holder.getLegalCapacityNote());
                    y -= 13;
                }
                if (hasBlocks) {
                    write(c, MARGIN, y, fontRegular, 10, "Sperrvermerke / Registered encumbrances:");
                    y -= 13;
                    for (HolderBlock b : blocks) {
                        // Structured registry-layer blocks (pledges, seizures, insolvency, court
                        // orders, …) can grow unboundedly, unlike the fixed fields above — flip
                        // to a fresh page rather than silently truncating a legal disclosure.
                        if (y < MARGIN + 60) {
                            write(c, MARGIN, y, fontRegular, 8, "… (Fortsetzung nächste Seite / continued on next page)");
                            c.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            c = new PDPageContentStream(doc, page);
                            y = PAGE_HEIGHT - MARGIN;
                            write(c, MARGIN, y, fontBold, 11, "Sperrvermerke / Registered encumbrances (Fortsetzung / continued)");
                            y -= 18;
                        }
                        write(c, MARGIN, y, fontRegular, 9,
                                "  • " + b.getBlockType() + " — " + safe(b.getLegalBasis())
                                        + (hasText(b.getCourtRef()) ? " (" + b.getCourtRef() + ")" : "")
                                        + " — seit / since " + instantDate(b.getStartsAt()));
                        y -= 12;
                    }
                }
                y -= 11;
            }

            // Non-statutory documents (collective holdings, or LU/LI labeled analogues)
            // must never read as if they were the statutory § 19 eWpG document.
            if (!profile.statutory() && hasText(profile.disclaimerNote())) {
                if (y < MARGIN + 90) {
                    c.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = PAGE_HEIGHT - MARGIN;
                }
                write(c, MARGIN, y, fontBold, 9, "Hinweis / Notice:");
                y -= 12;
                y = writeWrapped(c, MARGIN, y, fontRegular, 8, profile.disclaimerNote(), PAGE_WIDTH - 2 * MARGIN);
                y -= 12;
            }

            // ── Footer ────────────────────────────────────────────────────
            float footerY = MARGIN + 24;
            c.moveTo(MARGIN, footerY + 12);
            c.lineTo(PAGE_WIDTH - MARGIN, footerY + 12);
            c.stroke();
            write(c, MARGIN, footerY, fontRegular, 8,
                    "Dieses Dokument wurde elektronisch in Textform erstellt. "
                            + safe(registryName) + " — " + safe(registryCountry));
            c.close();

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

    private static String registerTypeLabel(Asset asset) {
        return asset.getOnchainLevel() == OnchainLevel.NONE
                ? "Zentralregister / Central register (kein Onchain-Bestand)"
                : "Kryptowertpapierregister / Crypto securities register (§16 eWpG)";
    }

    private static String issuerLabel(LegalEntity issuer) {
        if (issuer == null) return "—";
        return safe(issuer.getCurrentName()) + " (" + safe(issuer.getEntityNumber()) + ")";
    }

    private static String volumeLabel(AssetBondTerms bondTerms) {
        if (bondTerms == null || bondTerms.getFaceValue() == null) return "—";
        String currency = bondTerms.getCurrencyIso() != null ? bondTerms.getCurrencyIso() : "";
        return bondTerms.getFaceValue().toPlainString() + " " + currency;
    }

    private static String termSheetLabel(AssetDocument termSheet) {
        if (termSheet == null) return "— (nicht hinterlegt / not on file)";
        return safe(termSheet.getFileName()) + " (Hash: " + safe(termSheet.getContentHash()) + ")";
    }

    private static String instantDate(Instant instant) {
        return instant != null ? DATE_FMT.format(instant.atZone(ZoneOffset.UTC).toLocalDate()) : "—";
    }

    private static void write(PDPageContentStream c, float x, float y,
                              PDType1Font font, float size, String text) throws IOException {
        c.setFont(font, size);
        c.beginText();
        c.newLineAtOffset(x, y);
        c.showText(text != null ? text : "");
        c.endText();
    }

    /** Naive word-wrap for the one field (the disclaimer) long enough to need it. */
    private static float writeWrapped(PDPageContentStream c, float x, float y,
                                      PDType1Font font, float size, String text, float maxWidth) throws IOException {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            float width = font.getStringWidth(candidate) / 1000 * size;
            if (width > maxWidth && !line.isEmpty()) {
                write(c, x, y, font, size, line.toString());
                y -= size + 2;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            write(c, x, y, font, size, line.toString());
            y -= size + 2;
        }
        return y;
    }

    private static String safe(String s) {
        return s != null && !s.isBlank() ? s : "—";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
