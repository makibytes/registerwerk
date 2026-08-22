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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates corporate-action confirmation PDFs — a document type that was entirely missing:
 * {@code CorporateActionAdminController} only ever exposed a list + settlement-approval
 * endpoint, with nothing confirming to an issuer or investor that a coupon/dividend/redemption
 * actually paid out. Built on the same {@link CorporateActionEntry} per-holder ledger that now
 * backs {@link SteuerbescheinigungService}.
 *
 * <p>Two variants: the operator-facing confirmation lists every holder's entry (a settlement
 * audit document); the investor-facing one is scoped to that investor's own entry only.
 */
@Service
public class CorporateActionConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionConfirmationService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final float MARGIN = 50;

    private final CorporateActionRepository actionRepository;
    private final CorporateActionEntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final DocumentSigningService signingService;
    private final FinalityGate finalityGate;

    CorporateActionConfirmationService(CorporateActionRepository actionRepository,
                                        CorporateActionEntryRepository entryRepository,
                                        AssetRepository assetRepository,
                                        LegalEntityRepository entityRepository,
                                        DocumentSigningService signingService,
                                        FinalityGate finalityGate) {
        this.actionRepository = actionRepository;
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.signingService = signingService;
        this.finalityGate = finalityGate;
    }

    /** Operator-facing: every holder's entry for this corporate action. */
    @Transactional(readOnly = true)
    public byte[] generateForOperator(UUID corporateActionId) {
        CorporateAction action = requireSettled(corporateActionId);
        List<CorporateActionEntry> entries = entryRepository.findByCorporateActionId(corporateActionId);
        Asset asset = assetRepository.findById(action.getAssetId()).orElse(null);
        Map<UUID, LegalEntity> entitiesById = entityRepository.findAllById(
                entries.stream().map(CorporateActionEntry::getInvestorId).filter(java.util.Objects::nonNull).toList()
        ).stream().collect(Collectors.toMap(LegalEntity::getId, Function.identity()));

        log.info("Generating corporate-action confirmation (operator) for id={} entries={}", corporateActionId, entries.size());
        try {
            return sign(buildPdf(action, asset, entries, entitiesById, null), action, "operator confirmation");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render corporate-action confirmation PDF", e);
        }
    }

    /** Investor-facing: scoped to this investor's own entry only. */
    @Transactional(readOnly = true)
    public byte[] generateForInvestor(UUID corporateActionId, UUID investorId) {
        CorporateAction action = requireSettled(corporateActionId);
        List<CorporateActionEntry> all = entryRepository.findByCorporateActionId(corporateActionId);
        List<CorporateActionEntry> own = all.stream()
                .filter(e -> investorId.equals(e.getInvestorId()))
                .toList();
        if (own.isEmpty()) {
            throw new EntityNotFoundException("CorporateActionEntry for investor", investorId);
        }
        Asset asset = assetRepository.findById(action.getAssetId()).orElse(null);
        LegalEntity investor = entityRepository.findById(investorId).orElse(null);
        Map<UUID, LegalEntity> entitiesById = investor != null ? Map.of(investorId, investor) : Map.of();

        log.info("Generating corporate-action confirmation (investor) for id={} investor={}", corporateActionId, investorId);
        try {
            return sign(buildPdf(action, asset, own, entitiesById, investorId), action, "investor confirmation");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render corporate-action confirmation PDF", e);
        }
    }

    /** Operator-facing: ISO 20022-shaped notification covering every holder's entry — see
     *  {@link Iso20022CorporateActionNotificationRenderer} for the scoping caveat. */
    @Transactional(readOnly = true)
    public byte[] generateIso20022ForOperator(UUID corporateActionId) {
        CorporateAction action = requireSettled(corporateActionId);
        List<CorporateActionEntry> entries = entryRepository.findByCorporateActionId(corporateActionId);
        Asset asset = assetRepository.findById(action.getAssetId()).orElse(null);
        Map<UUID, LegalEntity> entitiesById = entityRepository.findAllById(
                entries.stream().map(CorporateActionEntry::getInvestorId).filter(java.util.Objects::nonNull).toList()
        ).stream().collect(Collectors.toMap(LegalEntity::getId, Function.identity()));
        return Iso20022CorporateActionNotificationRenderer.render(action, asset, entries, entitiesById);
    }

    /** Investor-facing: ISO 20022-shaped notification scoped to this investor's own entry. */
    @Transactional(readOnly = true)
    public byte[] generateIso20022ForInvestor(UUID corporateActionId, UUID investorId) {
        CorporateAction action = requireSettled(corporateActionId);
        List<CorporateActionEntry> own = entryRepository.findByCorporateActionId(corporateActionId).stream()
                .filter(e -> investorId.equals(e.getInvestorId()))
                .toList();
        if (own.isEmpty()) {
            throw new EntityNotFoundException("CorporateActionEntry for investor", investorId);
        }
        Asset asset = assetRepository.findById(action.getAssetId()).orElse(null);
        LegalEntity investor = entityRepository.findById(investorId).orElse(null);
        Map<UUID, LegalEntity> entitiesById = investor != null ? Map.of(investorId, investor) : Map.of();
        return Iso20022CorporateActionNotificationRenderer.render(action, asset, own, entitiesById);
    }

    private byte[] sign(byte[] unsigned, CorporateAction action, String kind) {
        return signingService.isConfigured()
                ? signingService.signPdf(unsigned, "Corporate action confirmation (" + kind + ") — " + action.getId())
                : unsigned;
    }

    /**
     * Common chokepoint for all four {@code generate*} methods — also where
     * {@link GatedOperation#CORPORATE_ACTION_CONFIRMATION_EXPORT} is enforced:
     * a confirmation document is exactly the kind of thing that must not go out describing a
     * still-provisional settlement. {@code currentLevel} is {@code FINALIZED} unconditionally,
     * same reasoning as every other gate call site in this codebase — the action is already
     * required to be SETTLED/CLOSED here, and settlement itself is gated at
     * {@code CorporateActionService.confirmSettlementAsOperator}/{@code markSettledManually}.
     */
    private CorporateAction requireSettled(UUID corporateActionId) {
        CorporateAction action = actionRepository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (action.getStatus() != CorporateAction.Status.SETTLED && action.getStatus() != CorporateAction.Status.CLOSED) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " has not settled yet (status=" + action.getStatus() + ")");
        }
        TokenStandard standard = assetRepository.findById(action.getAssetId())
                .map(Asset::getTokenStandard).orElse(null);
        finalityGate.require(GatedOperation.CORPORATE_ACTION_CONFIRMATION_EXPORT, action.getAssetId(), standard, FinalityLevel.FINALIZED);
        return action;
    }

    private byte[] buildPdf(CorporateAction action, Asset asset, List<CorporateActionEntry> entries,
                             Map<UUID, LegalEntity> entitiesById, UUID scopedToInvestor) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float pageWidth = PDRectangle.A4.getWidth();

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream c = new PDPageContentStream(doc, page);
            float y = PDRectangle.A4.getHeight() - MARGIN;

            write(c, MARGIN, y, fontBold, 16, "Bestätigung Kapitalmaßnahme / Corporate Action Confirmation");
            y -= 20;
            write(c, MARGIN, y, fontRegular, 10, "Typ / Type: " + action.getActionType());
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10,
                    "Wertpapier / Security: " + safe(asset != null ? asset.getName() : null)
                            + " (ISIN " + safe(asset != null ? asset.getIsin() : null) + ")");
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Zahltag / Payment date: " + dateStr(action.getPaymentDate()));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Abrechnung / Settlement tx: " + safe(action.getSettlementTxHash()));
            y -= 14;
            write(c, MARGIN, y, fontRegular, 10, "Abgerechnet am / Settled at: " + instantStr(action.getSettledAt()));
            y -= 20;

            if (scopedToInvestor != null) {
                LegalEntity investor = entitiesById.get(scopedToInvestor);
                write(c, MARGIN, y, fontBold, 11, "Empfänger / Recipient:");
                y -= 14;
                write(c, MARGIN, y, fontRegular, 10, safe(investor != null ? investor.getEntityNumber() : null));
                y -= 20;
            }

            write(c, MARGIN, y, fontBold, 11, "Zahlungen / Payouts:");
            y -= 5;
            c.moveTo(MARGIN, y);
            c.lineTo(pageWidth - MARGIN, y);
            c.stroke();
            y -= 12;

            float[] cols = {MARGIN, 220, 340, 440};
            write(c, cols[0], y, fontBold, 9, "Anleger / Investor");
            write(c, cols[1], y, fontBold, 9, "Nennbetrag (Record)");
            write(c, cols[2], y, fontBold, 9, "Auszahlung");
            write(c, cols[3], y, fontBold, 9, "Wallet");
            y -= 12;

            for (CorporateActionEntry entry : entries) {
                if (y < MARGIN + 60) {
                    c.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = PDRectangle.A4.getHeight() - MARGIN;
                    write(c, MARGIN, y, fontBold, 11, "Zahlungen (Fortsetzung / continued):");
                    y -= 16;
                }
                writeRow(c, cols, y, fontRegular, entry, entitiesById);
                y -= 12;
            }

            y -= 10;
            c.moveTo(MARGIN, y);
            c.lineTo(pageWidth - MARGIN, y);
            c.stroke();
            y -= 20;

            String signatureClaim = signingService.isConfigured()
                    ? "Dieses Dokument wird digital signiert (PAdES-B-B, CMS/PKCS#7)."
                    : "Dieses Dokument ist NICHT digital signiert (kein Signaturzertifikat konfiguriert).";
            write(c, MARGIN, MARGIN + 20, fontRegular, 8,
                    "Dieses Dokument wurde elektronisch erstellt. " + signatureClaim + " Registerwerk eWpG-Registry.");
            c.close();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private void writeRow(PDPageContentStream c, float[] cols, float y, PDType1Font font,
                          CorporateActionEntry entry, Map<UUID, LegalEntity> entitiesById) throws IOException {
        LegalEntity investor = entry.getInvestorId() != null ? entitiesById.get(entry.getInvestorId()) : null;
        write(c, cols[0], y, font, 8, safe(investor != null ? investor.getEntityNumber() : null));
        write(c, cols[1], y, font, 8, entry.getNominalAtRecord() != null ? entry.getNominalAtRecord().toPlainString() : "0");
        write(c, cols[2], y, font, 8, entry.getEntitlementAmount() != null
                ? entry.getEntitlementAmount().setScale(2, RoundingMode.HALF_UP).toPlainString() : "—");
        String wallet = entry.getWalletAddress();
        write(c, cols[3], y, font, 8, wallet != null ? wallet.substring(0, Math.min(16, wallet.length())) + "…" : "—");
    }

    private static void write(PDPageContentStream c, float x, float y, PDType1Font font, float size, String text) throws IOException {
        c.setFont(font, size);
        c.beginText();
        c.newLineAtOffset(x, y);
        c.showText(text != null ? text : "");
        c.endText();
    }

    private static String safe(String s) {
        return s != null && !s.isBlank() ? s : "—";
    }

    private static String dateStr(LocalDate d) {
        return d != null ? d.format(DATE_FMT) : "—";
    }

    private static String instantStr(Instant i) {
        return i != null ? DATE_FMT.format(i.atZone(ZoneOffset.UTC).toLocalDate()) : "—";
    }
}
