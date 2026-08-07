package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.customer.api.LegalEntity;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a corporate-action confirmation XML message, structurally modeled on the ISO 20022
 * {@code seev.031} (CorporateActionNotification) message family — element names and nesting
 * follow that message's well-documented shape, but this is <b>not validated against the official
 * ISO 20022 XSD</b> (no access to the authoritative schema registry at iso20022.org from this
 * environment). Used here post-settlement as a payout confirmation, which in a real deployment
 * more precisely maps to a movement-confirmation message (e.g. seev.035) — exact message-type
 * selection needs sign-off against the receiving bank's own ISO 20022 usage guide. Sits alongside
 * {@link CorporateActionConfirmationService}'s PDF rendering, reusing the same settled-entries data.
 */
final class Iso20022CorporateActionNotificationRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private Iso20022CorporateActionNotificationRenderer() {}

    static byte[] render(CorporateAction action, Asset asset, List<CorporateActionEntry> entries,
                          Map<UUID, LegalEntity> entitiesById) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("Document");
            w.writeDefaultNamespace("urn:iso:std:iso:20022:tech:xsd:seev.031.001.13");

            w.writeStartElement("CorpActnNtfctn");

            w.writeStartElement("CorpActnGnlInf");
            text(w, "CorpActnEvtId", action.getId().toString());
            text(w, "EvtTp", action.getActionType().name());
            w.writeEndElement();

            w.writeStartElement("FinInstrmId");
            text(w, "ISIN", asset != null ? asset.getIsin() : null);
            text(w, "Desc", asset != null ? asset.getName() : null);
            w.writeEndElement();

            w.writeStartElement("CorpActnDtls");
            w.writeStartElement("DtDtls");
            date(w, "RcrdDt", action.getRecordDate());
            date(w, "ExDvddDt", action.getExDate());
            date(w, "PmtDt", action.getPaymentDate());
            w.writeEndElement();
            w.writeStartElement("RateAndAmtDtls");
            amount(w, "GrssRate", action.getCurrency(), action.getAmountPerUnit());
            w.writeEndElement();
            w.writeEndElement(); // CorpActnDtls

            for (CorporateActionEntry entry : entries) {
                LegalEntity investor = entry.getInvestorId() != null ? entitiesById.get(entry.getInvestorId()) : null;
                w.writeStartElement("AcctDtls");
                w.writeStartElement("AcctOwnr");
                text(w, "Id", investor != null ? investor.getEntityNumber() : (entry.getInvestorId() != null ? entry.getInvestorId().toString() : null));
                w.writeEndElement();
                w.writeStartElement("BalFr");
                text(w, "Qty", entry.getNominalAtRecord() != null ? entry.getNominalAtRecord().toPlainString() : null);
                w.writeEndElement();
                w.writeStartElement("EntitldQty");
                amount(w, "Amt", action.getCurrency(), entry.getEntitlementAmount());
                w.writeEndElement();
                w.writeEndElement(); // AcctDtls
            }

            w.writeEndElement(); // CorpActnNtfctn
            w.writeEndElement(); // Document
            w.writeEndDocument();
            w.flush();
            w.close();
            return out.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to render ISO 20022 corporate action notification", e);
        }
    }

    private static void amount(XMLStreamWriter w, String tag, String currency, java.math.BigDecimal value) throws XMLStreamException {
        w.writeStartElement(tag);
        w.writeAttribute("Ccy", currency != null ? currency : "EUR");
        w.writeCharacters(value != null ? value.toPlainString() : "0");
        w.writeEndElement();
    }

    private static void date(XMLStreamWriter w, String tag, java.time.LocalDate value) throws XMLStreamException {
        if (value == null) {
            return;
        }
        text(w, tag, value.format(DATE_FMT));
    }

    private static void text(XMLStreamWriter w, String tag, String value) throws XMLStreamException {
        w.writeStartElement(tag);
        w.writeCharacters(value != null ? value : "");
        w.writeEndElement();
    }
}
