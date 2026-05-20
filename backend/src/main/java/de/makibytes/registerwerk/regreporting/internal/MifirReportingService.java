package de.makibytes.registerwerk.regreporting.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * MiFIR Art. 26 / Commission Delegated Regulation (EU) 2017/590 (RTS 22) transaction reporting.
 * Applies to: DE_EWPG and FR_AMF jurisdictions where eWpG tokens qualify as MiFID II instruments.
 * Reporting destinations: BaFin MeldewesenPortal (DE), AMF ROSA (FR), CSSF (LU via ESMA ARM).
 *
 * Flow: TradeExecutedEvent → daily XML export → file via SFTP/API → record in regreport_submission.
 */
@Service
public class MifirReportingService {

    private static final Logger log = LoggerFactory.getLogger(MifirReportingService.class);

    private final JdbcTemplate jdbc;
    private final RegReportSubmissions submissions;

    MifirReportingService(JdbcTemplate jdbc, RegReportSubmissions submissions) {
        this.jdbc = jdbc;
        this.submissions = submissions;
    }

    /**
     * Daily T+1 export of trade executions for MiFIR RTS 22 reporting.
     * Runs at 06:00 UTC; covers T-1 executions (settlement date basis for eWpG tokens).
     */
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional(readOnly = true)
    public void generateDailyReport() {
        LocalDate reportingDate = LocalDate.now().minusDays(1);
        log.info("MiFIR RTS 22: generating daily report for date={}", reportingDate);
        generateForJurisdiction("DE_BAFIN", reportingDate);
        generateForJurisdiction("FR_AMF", reportingDate);
    }

    private void generateForJurisdiction(String jurisdiction, LocalDate reportingDate) {
        var rows = jdbc.queryForList("""
            SELECT te.id, te.asset_id, te.buyer_entity_id, te.seller_entity_id,
                   te.price, te.quantity, te.executed_at, a.isin, a.name
            FROM trade_execution te
            JOIN asset a ON a.id = te.asset_id
            WHERE DATE(te.executed_at) = ?
              AND a.status = 'ISSUED'
            ORDER BY te.executed_at
            """, reportingDate);

        if (rows.isEmpty()) {
            log.debug("MiFIR RTS 22 {}: no trades on {}", jurisdiction, reportingDate);
            return;
        }

        String xml = buildRts22Xml(rows, jurisdiction, reportingDate);
        UUID submissionId = submissions.persist("MIFIR_RTS22", jurisdiction, reportingDate, reportingDate);

        log.info("MiFIR RTS 22 {}: {} trades queued for submission id={}", jurisdiction, rows.size(), submissionId);
        // TODO: file via SFTP/ESMA ARM API — implement SubmissionGateway per jurisdiction
    }

    @SuppressWarnings("unchecked")
    private String buildRts22Xml(java.util.List<java.util.Map<String, Object>> rows,
                                  String jurisdiction, LocalDate reportingDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:auth.016.001.03\">\n");
        sb.append("  <FinInstrmRptgTxRpt>\n");
        sb.append("    <RptHdr>\n");
        sb.append("      <RptgNtty><LEI>TODO_OPERATOR_LEI</LEI></RptgNtty>\n");
        sb.append("      <RptgPrd><Dt>").append(reportingDate).append("</Dt></RptgPrd>\n");
        sb.append("    </RptHdr>\n");

        for (var row : rows) {
            sb.append("    <Tx>\n");
            sb.append("      <New>\n");
            sb.append("        <TxId>").append(RegReportSubmissions.esc(row.get("id"))).append("</TxId>\n");
            sb.append("        <FinInstrm><Id><ISIN>").append(RegReportSubmissions.esc(row.get("isin"))).append("</ISIN></Id></FinInstrm>\n");
            sb.append("        <Qty><Unit>").append(RegReportSubmissions.esc(row.get("quantity"))).append("</Unit></Qty>\n");
            sb.append("        <Pric><Pric><Amt Ccy=\"EUR\">").append(RegReportSubmissions.esc(row.get("price"))).append("</Amt></Pric></Pric>\n");
            sb.append("        <TradDt>").append(RegReportSubmissions.esc(row.get("executed_at"))).append("</TradDt>\n");
            sb.append("      </New>\n");
            sb.append("    </Tx>\n");
        }

        sb.append("  </FinInstrmRptgTxRpt>\n");
        sb.append("</Document>");
        return sb.toString();
    }
}
