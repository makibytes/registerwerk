package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.api.SubmissionGateway;
import de.makibytes.registerwerk.regreporting.api.SubmissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Draft/unvalidated MiFIR-like transaction export prototype.
 *
 * <p>The population, fields and XML are not an official RTS 22 implementation. Instrument
 * classification, reporting-entity responsibility, competent-authority routing, official-schema
 * validation, corrections and authenticated receipts remain external release blockers.
 *
 * <p>Flow: trade projection → draft XML → persist → optional transport → transport-only status.
 */
@Service
public class MifirReportingService {

    private static final Logger log = LoggerFactory.getLogger(MifirReportingService.class);

    private final JdbcTemplate jdbc;
    private final RegReportSubmissions submissions;
    private final S3DocumentStore documentStore;
    private final SubmissionGateway submissionGateway;
    private final ReportingProperties reportingProperties;

    MifirReportingService(JdbcTemplate jdbc, RegReportSubmissions submissions,
                          S3DocumentStore documentStore, SubmissionGateway submissionGateway,
                          ReportingProperties reportingProperties) {
        this.jdbc = jdbc;
        this.submissions = submissions;
        this.documentStore = documentStore;
        this.submissionGateway = submissionGateway;
        this.reportingProperties = reportingProperties;
    }

    /**
     * Daily draft projection of trade rows created on the previous calendar date.
     * This is not an RTS 22 population or a settlement-date report.
     */
    @SchedulerLock(name = "mifirReporting", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void generateDailyReport() {
        if (!reportingProperties.isPrototypeEnabled()) {
            log.debug("MiFIR draft export skipped because the reporting prototype is disabled");
            return;
        }
        generateDailyReport(LocalDate.now().minusDays(1), null, "SYSTEM");
    }

    /** On-demand generation for an explicit date — previously the date parameter a caller
     *  supplied was silently discarded and this always ran for {@code now().minusDays(1)}. */
    @Transactional
    public void generateDailyReport(LocalDate reportingDate, UUID actorId, String actorRole) {
        requirePrototypeEnabled();
        log.info("MiFIR-like DRAFT_UNVALIDATED export: generating for date={}", reportingDate);
        // These are prototype routing labels, not a verified competent-authority decision.
        generateForJurisdiction("DE_BAFIN", "DE_EWPG", reportingDate, actorId, actorRole);
        generateForJurisdiction("FR_AMF", "FR_AMF", reportingDate, actorId, actorRole);
    }

    private void generateForJurisdiction(String authority, String assetJurisdiction, LocalDate reportingDate,
                                          UUID actorId, String actorRole) {
        var rows = jdbc.queryForList("""
            SELECT te.id, te.asset_id, te.buyer_entity_id, te.seller_entity_id,
                   te.unit_price AS price, te.executed_quantity AS quantity,
                   te.created_at AS executed_at, a.isin, a.name
            FROM trade_execution te
            JOIN asset a ON a.id = te.asset_id
            WHERE DATE(te.created_at) = ?
              AND a.status = 'ISSUED'
              AND a.jurisdiction = ?
            ORDER BY te.created_at
            """, reportingDate, assetJurisdiction);

        if (rows.isEmpty()) {
            log.debug("MiFIR-like draft {}: no projected trade rows on {}", authority, reportingDate);
            return;
        }

        String xml = buildRts22Xml(rows, reportingDate);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        UUID submissionId = submissions.persist("MIFIR_RTS22", authority, reportingDate, reportingDate, actorId);
        documentStore.store(submissionId, "MIFIR_RTS22", authority, reportingDate, xmlBytes);

        SubmissionResult result = submissionGateway.submit(submissionId, "MIFIR_RTS22", authority, xmlBytes);
        applyResult(submissionId, result, actorId, actorRole);
        log.info("MiFIR-like draft {}: {} projected trades processed, export id={} transportStatus={}",
                authority, rows.size(), submissionId, result.status());
    }

    @SuppressWarnings("unchecked")
    private String buildRts22Xml(java.util.List<java.util.Map<String, Object>> rows,
                                  LocalDate reportingDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!-- DRAFT_UNVALIDATED: not validated against an official RTS 22 schema; not filing evidence. -->\n");
        sb.append("<Document xmlns=\"urn:registerwerk:prototype:mifir:draft-unvalidated:v1\" validationStatus=\"DRAFT_UNVALIDATED\">\n");
        sb.append("  <FinInstrmRptgTxRpt>\n");
        sb.append("    <RptHdr>\n");
        sb.append("      <RptgNtty><LEI>").append(RegReportSubmissions.esc(reportingProperties.getOperatorLei())).append("</LEI></RptgNtty>\n");
        sb.append("      <RptgPrd><Dt>").append(reportingDate).append("</Dt></RptgPrd>\n");
        sb.append("    </RptHdr>\n");

        for (var row : rows) {
            sb.append("    <Tx>\n");
            sb.append("      <New>\n");
            sb.append("        <TxId>").append(RegReportSubmissions.esc(row.get("id"))).append("</TxId>\n");
            sb.append("        <FinInstrm><Id><ISIN>").append(RegReportSubmissions.esc(row.get("isin"))).append("</ISIN></Id></FinInstrm>\n");
            sb.append("        <Qty><Unit>").append(RegReportSubmissions.esc(row.get("quantity"))).append("</Unit></Qty>\n");
            sb.append("        <Pric><Pric><Amt Ccy=\"EUR\">").append(RegReportSubmissions.esc(row.get("price"))).append("</Amt></Pric></Pric>\n");
            sb.append("        <TradDt>").append(toIso8601(row.get("executed_at"))).append("</TradDt>\n");
            sb.append("      </New>\n");
            sb.append("    </Tx>\n");
        }

        sb.append("  </FinInstrmRptgTxRpt>\n");
        sb.append("</Document>");
        return sb.toString();
    }

    /** Preserve unambiguous UTC timestamps in the draft output. */
    private static String toIso8601(Object executedAt) {
        if (executedAt instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        return RegReportSubmissions.esc(executedAt);
    }

    private void applyResult(UUID submissionId, SubmissionResult result, UUID actorId, String actorRole) {
        switch (result.status()) {
            case TRANSPORTED_UNVERIFIED -> submissions.markTransportedUnverified(
                    submissionId, result.transportRef(), actorId, actorRole);
            case NOT_TRANSPORTED -> submissions.markNotTransported(
                    submissionId, result.errorMessage(), actorId, actorRole);
            case TRANSPORT_FAILED -> submissions.markTransportFailed(
                    submissionId, result.errorMessage(), actorId, actorRole);
        }
    }

    private void requirePrototypeEnabled() {
        if (!reportingProperties.isPrototypeEnabled()) {
            throw new IllegalStateException("Regulatory reporting prototype is disabled. "
                    + "Generated documents would be DRAFT_UNVALIDATED and are not filing evidence.");
        }
    }
}
