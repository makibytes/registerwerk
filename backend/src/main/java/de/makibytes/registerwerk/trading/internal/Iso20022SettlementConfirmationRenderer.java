package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.trading.api.TradeExecution;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generates a trade settlement confirmation XML message, structurally modeled on the ISO 20022
 * {@code sese.032} (SecuritiesSettlementTransactionConfirmation) message family — element names
 * and nesting follow that message's well-documented shape, but this is <b>not validated against
 * the official ISO 20022 XSD</b> (this environment has no access to the authoritative schema
 * registry at iso20022.org) and omits BIC/party-identification detail a live SWIFT or Fundsettle
 * network integration would require. It closes the "no ISO 20022 bridge" structural gap for the
 * one workflow with clean, already-modeled settlement data ({@link TradeExecution} — see the
 * {@code TradeConfirmationPdfRenderer} it sits alongside), not a certified interoperability
 * implementation. Message-type selection and full schema conformance need sign-off against the
 * receiving bank's own ISO 20022 usage guide.
 */
final class Iso20022SettlementConfirmationRenderer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private Iso20022SettlementConfirmationRenderer() {}

    static byte[] render(java.util.UUID executionId, TradeExecution execution, LegalEntity buyer, LegalEntity seller) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("Document");
            w.writeDefaultNamespace("urn:iso:std:iso:20022:tech:xsd:sese.032.001.13");

            w.writeStartElement("SctiesSttlmTxConf");

            w.writeStartElement("TxId");
            text(w, "AcctOwnrTxId", executionId.toString());
            w.writeEndElement();

            w.writeStartElement("TxDtls");

            w.writeStartElement("SttlmTpAndAddtlParams");
            text(w, "SctiesMvmntTp", "DELI");
            text(w, "Pmt", "APMT");
            w.writeEndElement();

            w.writeStartElement("TradDtls");
            if (execution.getSettledAt() != null) {
                w.writeStartElement("SttlmDt");
                text(w, "Dt", execution.getSettledAt().atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT));
                w.writeEndElement();
            }
            w.writeStartElement("DealPric");
            amount(w, "Amt", execution.getUnitPrice());
            w.writeEndElement();
            w.writeEndElement(); // TradDtls

            w.writeStartElement("FinInstrmId");
            text(w, "ISIN", execution.getIsin());
            text(w, "Desc", execution.getAssetName());
            w.writeEndElement();

            w.writeStartElement("QtyAndAcctDtls");
            w.writeStartElement("SttlmQty");
            text(w, "Unit", execution.getExecutedQuantity() != null ? execution.getExecutedQuantity().toPlainString() : null);
            w.writeEndElement();
            w.writeEndElement(); // QtyAndAcctDtls

            w.writeStartElement("SttlmAmt");
            amount(w, "Amt", execution.getTotalPrice());
            w.writeEndElement();

            w.writeStartElement("DlvrgSttlmPties");
            party(w, seller, execution.getSellerEntityId().toString());
            w.writeEndElement();

            w.writeStartElement("RcvgSttlmPties");
            party(w, buyer, execution.getBuyerEntityId().toString());
            w.writeEndElement();

            w.writeEndElement(); // TxDtls
            w.writeEndElement(); // SctiesSttlmTxConf
            w.writeEndElement(); // Document
            w.writeEndDocument();
            w.flush();
            w.close();
            return out.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to render ISO 20022 settlement confirmation", e);
        }
    }

    private static void party(XMLStreamWriter w, LegalEntity entity, String fallbackId) throws XMLStreamException {
        w.writeStartElement("PtyId");
        text(w, "Id", entity != null ? entity.getEntityNumber() : fallbackId);
        w.writeEndElement();
    }

    private static void amount(XMLStreamWriter w, String tag, java.math.BigDecimal value) throws XMLStreamException {
        w.writeStartElement(tag);
        w.writeAttribute("Ccy", "EUR");
        w.writeCharacters(value != null ? value.toPlainString() : "0");
        w.writeEndElement();
    }

    private static void text(XMLStreamWriter w, String tag, String value) throws XMLStreamException {
        w.writeStartElement(tag);
        w.writeCharacters(value != null ? value : "");
        w.writeEndElement();
    }
}
