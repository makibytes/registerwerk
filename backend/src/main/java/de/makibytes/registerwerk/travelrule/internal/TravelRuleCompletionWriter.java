package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.events.TravelRuleMessageSentEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Persists asynchronous protocol results and their audit event in one transaction. */
@Component
public class TravelRuleCompletionWriter {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public TravelRuleCompletionWriter(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public void markFailed(UUID messageId, String errorMessage) {
        jdbc.update("""
                UPDATE travel_rule_message SET status=?, error_message=?, updated_at=now()
                WHERE id=? AND status=?
                """, TravelRuleService.STATUS_FAILED, errorMessage, messageId,
                TravelRuleService.STATUS_PENDING_SEND);
    }

    @Transactional
    public void markSent(UUID messageId, String protocolName, String protocolMessageId, String beneficiaryVaspId) {
        int updated = jdbc.update("""
            UPDATE travel_rule_message
            SET status=?, protocol=?, protocol_message_id=?, sent_at=now(), updated_at=now()
            WHERE id=? AND status=?
            """, TravelRuleService.STATUS_SENT, protocolName, protocolMessageId, messageId,
                TravelRuleService.STATUS_PENDING_SEND);
        if (updated == 1) {
            events.publishEvent(new TravelRuleMessageSentEvent(messageId, null, "SYSTEM", Map.of(
                    "protocol", protocolName != null ? protocolName : "",
                    "beneficiaryVaspId", beneficiaryVaspId != null ? beneficiaryVaspId : "")));
        }
    }
}
