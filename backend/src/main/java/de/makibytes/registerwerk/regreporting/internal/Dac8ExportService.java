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
 * Draft/unvalidated DAC8/CARF-like annual export prototype.
 *
 * <p>The current holdings query is not a reportable-user diligence or transaction-flow model,
 * the XML is not an official schema, and the jurisdiction labels are not verified routing
 * decisions. This service must not be represented as a DAC8/KStTG filing integration.
 */
@Service
public class Dac8ExportService {

    private static final Logger log = LoggerFactory.getLogger(Dac8ExportService.class);
    private final JdbcTemplate jdbc;
    private final RegReportSubmissions submissions;
    private final S3DocumentStore documentStore;
    private final SubmissionGateway submissionGateway;
    private final ReportingProperties reportingProperties;

    Dac8ExportService(JdbcTemplate jdbc, RegReportSubmissions submissions,
                      S3DocumentStore documentStore, SubmissionGateway submissionGateway,
                      ReportingProperties reportingProperties) {
        this.jdbc = jdbc;
        this.submissions = submissions;
        this.documentStore = documentStore;
        this.submissionGateway = submissionGateway;
        this.reportingProperties = reportingProperties;
    }

    /**
     * Annual scheduled draft of the current holder projection, labelled with the prior year.
     * It is not a CARF/DAC8/KStTG reportable-user or transaction-flow population.
     */
    @SchedulerLock(name = "dac8Export", lockAtMostFor = "PT1H")
    @Scheduled(cron = "0 0 4 31 1 *")
    @Transactional
    public void generateAnnualCarf() {
        if (!reportingProperties.isPrototypeEnabled()) {
            log.debug("DAC8/CARF-like draft export skipped because the reporting prototype is disabled");
            return;
        }
        generateAnnualCarf(LocalDate.now().getYear() - 1, null, "SYSTEM");
    }

    /** On-demand generation for an explicit tax year — previously the year parameter a caller
     *  supplied was silently discarded and this always ran for {@code now().getYear() - 1}. */
    @Transactional
    public void generateAnnualCarf(int reportingYear, UUID actorId, String actorRole) {
        requirePrototypeEnabled();
        log.info("DAC8/CARF-like DRAFT_UNVALIDATED export: generating for tax year {}", reportingYear);

        for (String jurisdiction : new String[]{"DE_BAFIN", "LU_CSSF", "FR_AMF", "LI_FMA"}) {
            try {
                generateCarfForJurisdiction(jurisdiction, reportingYear, actorId, actorRole);
            } catch (Exception e) {
                log.error("DAC8/CARF {}: export failed for year {}: {}", jurisdiction, reportingYear, e.getMessage());
            }
        }
    }

    private void generateCarfForJurisdiction(String jurisdiction, int year, UUID actorId, String actorRole) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        var holdings = jdbc.queryForList("""
            SELECT ah.investor_id, le.entity_number, le.registration_country,
                   a.isin, a.name, a.token_standard,
                   SUM(ah.nominal_amount) as total_nominal,
                   COUNT(DISTINCT tt.tx_hash) as tx_count
            FROM asset_holder ah
            JOIN asset a ON a.id = ah.asset_id
            JOIN legal_entity le ON le.id = ah.investor_id
            LEFT JOIN token_transfer tt ON tt.to_address = ah.wallet_address
                AND DATE(tt.created_at) BETWEEN ? AND ?
            WHERE a.status = 'ISSUED'
            GROUP BY ah.investor_id, le.entity_number, le.registration_country,
                     a.isin, a.name, a.token_standard
            HAVING SUM(ah.nominal_amount) > 0
            """, yearStart, yearEnd);

        if (holdings.isEmpty()) {
            log.debug("DAC8/CARF-like draft {}: no projected holder rows for label year {}", jurisdiction, year);
            return;
        }

        String xml = buildCarfXml(holdings, jurisdiction, year);
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        UUID submissionId = submissions.persist("DAC8_CARF", jurisdiction, yearStart, yearEnd, actorId);
        documentStore.store(submissionId, "DAC8_CARF", jurisdiction, yearEnd, xmlBytes);

        SubmissionResult result = submissionGateway.submit(submissionId, "DAC8_CARF", jurisdiction, xmlBytes);
        applyResult(submissionId, result, actorId, actorRole);
        log.info("DAC8/CARF-like draft {}: {} projected holders processed, export id={} transportStatus={}",
                jurisdiction, holdings.size(), submissionId, result.status());
    }

    @SuppressWarnings("unchecked")
    private String buildCarfXml(java.util.List<java.util.Map<String, Object>> holdings,
                                 String jurisdiction, int year) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!-- DRAFT_UNVALIDATED: not validated against an official DAC8/CARF/KStTG schema; not filing evidence. -->\n");
        sb.append("<CARFMessage xmlns=\"urn:registerwerk:prototype:carf:draft-unvalidated:v1\" validationStatus=\"DRAFT_UNVALIDATED\">\n");
        sb.append("  <MessageSpec>\n");
        sb.append("    <SendingEntityIN>").append(RegReportSubmissions.esc(reportingProperties.getOperatorTin())).append("</SendingEntityIN>\n");
        sb.append("    <MessageRefId>CARF-").append(jurisdiction).append("-").append(year).append("</MessageRefId>\n");
        sb.append("    <ReportingPeriod>").append(year).append("</ReportingPeriod>\n");
        sb.append("  </MessageSpec>\n");
        sb.append("  <CARFBody>\n");

        for (var row : holdings) {
            sb.append("    <ReportablePerson>\n");
            sb.append("      <EntityNumber>").append(RegReportSubmissions.esc(row.get("entity_number"))).append("</EntityNumber>\n");
            sb.append("      <ResCountryCode>").append(RegReportSubmissions.esc(row.get("registration_country"))).append("</ResCountryCode>\n");
            sb.append("      <Crypto><ISIN>").append(RegReportSubmissions.esc(row.get("isin"))).append("</ISIN>");
            sb.append("<Amount>").append(RegReportSubmissions.esc(row.get("total_nominal"))).append("</Amount></Crypto>\n");
            sb.append("    </ReportablePerson>\n");
        }

        sb.append("  </CARFBody>\n</CARFMessage>");
        return sb.toString();
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
