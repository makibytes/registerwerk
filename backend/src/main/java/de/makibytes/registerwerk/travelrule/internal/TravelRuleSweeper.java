package de.makibytes.registerwerk.travelrule.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Housekeeping for the Travel Rule tables (6-26/6-29): crash leftovers (PENDING_SEND) become FAILED with an
 * alert, queued FAILED rows are re-delivered with backoff, unmatched inbound messages are matched against
 * newly indexed transfers, and the inbound replay cache is purged.
 */
@Component
class TravelRuleSweeper {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleSweeper.class);

    private final TravelRuleCompletionWriter writer;
    private final TravelRuleService service;
    private final TravelRulePeerService peers;
    private final TravelRuleProperties properties;
    private final JdbcTemplate jdbc;

    TravelRuleSweeper(TravelRuleCompletionWriter writer, TravelRuleService service, TravelRulePeerService peers,
                      TravelRuleProperties properties, JdbcTemplate jdbc) {
        this.writer = writer;
        this.service = service;
        this.peers = peers;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "travelRuleSweeper", lockAtMostFor = "PT4M")
    void sweep() {
        run();
    }

    /** One pass; package-visible for tests. */
    void run() {
        List<UUID> stale = writer.sweepStalePending(properties.getStalePendingMinutes());
        if (!stale.isEmpty()) {
            log.error("Travel Rule: {} stale PENDING_SEND message(s) marked FAILED", stale.size());
        }
        List<Map<String, Object>> due = jdbc.queryForList("""
            SELECT id, ivms101_payload::text AS payload, beneficiary_wallet FROM travel_rule_message
             WHERE direction='OUTBOUND' AND status='FAILED' AND next_retry_at IS NOT NULL AND next_retry_at <= now()
             ORDER BY next_retry_at LIMIT 20
            """);
        for (Map<String, Object> row : due) {
            service.redeliver((UUID) row.get("id"), (String) row.get("payload"), (String) row.get("beneficiary_wallet"));
        }
        service.rematchInbound();
        peers.purgeReplayCache();
    }
}
