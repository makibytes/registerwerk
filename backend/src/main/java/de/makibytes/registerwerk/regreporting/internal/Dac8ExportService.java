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
 * DAC8 / Crypto-Asset Reporting Framework (CARF) annual export.
 * Council Directive (EU) 2023/2226 — applies to EU CASPs from 2026-01-01.
 * OECD CARF schema: https://www.oecd.org/tax/exchange-of-tax-information/crypto-asset-reporting-framework.htm
 *
 * Filing destinations: DE BZSt-Portal, LU ACD (Administration des Contributions Directes),
 * FR DGFiP, LI Steuerverwaltung.
 */
@Service
public class Dac8ExportService {

    private static final Logger log = LoggerFactory.getLogger(Dac8ExportService.class);
    private final JdbcTemplate jdbc;
    private final RegReportSubmissions submissions;

    Dac8ExportService(JdbcTemplate jdbc, RegReportSubmissions submissions) {
        this.jdbc = jdbc;
        this.submissions = submissions;
    }

    /**
     * Annual CARF export — runs on Jan 31 for the prior tax year (covers all CY holdings).
     * Per jurisdiction: one XML file per reporting country.
     */
    @Scheduled(cron = "0 0 4 31 1 *")
    @Transactional(readOnly = true)
    public void generateAnnualCarf() {
        int reportingYear = LocalDate.now().getYear() - 1;
        log.info("DAC8/CARF: generating annual export for tax year {}", reportingYear);

        for (String jurisdiction : new String[]{"DE_BAFIN", "LU_CSSF", "FR_AMF", "LI_FMA"}) {
            try {
                generateCarfForJurisdiction(jurisdiction, reportingYear);
            } catch (Exception e) {
                log.error("DAC8/CARF {}: export failed for year {}: {}", jurisdiction, reportingYear, e.getMessage());
            }
        }
    }

    private void generateCarfForJurisdiction(String jurisdiction, int year) {
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
            log.debug("DAC8/CARF {}: no reportable holdings for year {}", jurisdiction, year);
            return;
        }

        String xml = buildCarfXml(holdings, jurisdiction, year);
        UUID submissionId = submissions.persist("DAC8_CARF", jurisdiction, yearStart, yearEnd);
        log.info("DAC8/CARF {}: {} holders, submission id={}", jurisdiction, holdings.size(), submissionId);
        // TODO: file via jurisdiction-specific API (BZSt ELSTER, ACD portal, DGFiP DSN, LI portal)
    }

    @SuppressWarnings("unchecked")
    private String buildCarfXml(java.util.List<java.util.Map<String, Object>> holdings,
                                 String jurisdiction, int year) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<CARFMessage xmlns=\"urn:oecd:ties:carf:v1\">\n");
        sb.append("  <MessageSpec>\n");
        sb.append("    <SendingEntityIN>TODO_OPERATOR_TIN</SendingEntityIN>\n");
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
}
