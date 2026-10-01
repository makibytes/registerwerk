package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import de.makibytes.registerwerk.travelrule.events.TravelRuleMessageSentEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every write of an outbound message runs in its own committed transaction ({@code REQUIRES_NEW}).
 * Consequences (6-26): the PENDING_SEND row is durable before any HTTP call, so a result can never be
 * recorded against a row that is not yet visible, and a later rollback of the caller's operation cannot
 * erase the evidence that a message was (or was not) sent.
 */
@Component
public class TravelRuleCompletionWriter {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public TravelRuleCompletionWriter(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Parameters of a new outbound row; {@code payloadJson}/{@code detailsJson} are pre-serialized. */
    record NewMessage(UUID id, UUID assetId, String status, String beneficiaryVaspId, String fromWallet,
                      String toWallet, BigDecimal amount, String currencySymbol, String errorMessage,
                      String payloadJson, String detailsJson, String valuationSource, Instant valuationAt,
                      UUID walletProofId) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertOutbound(NewMessage m) {
        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, asset_id, beneficiary_vasp_did, originator_wallet,
               beneficiary_wallet, amount, currency_symbol, error_message, ivms101_payload,
               transfer_details, valuation_source, valuation_at, wallet_proof_id, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,now(),now())
            """,
            m.id(), "OUTBOUND", m.status(), m.assetId(), m.beneficiaryVaspId(), m.fromWallet(), m.toWallet(),
            m.amount(), m.currencySymbol(), m.errorMessage(), m.payloadJson(), m.detailsJson(),
            m.valuationSource(), m.valuationAt() == null ? null : java.sql.Timestamp.from(m.valuationAt()),
            m.walletProofId());
    }

    /** Audit row for an inbound message refused after authentication (e.g. blocked sender CASP). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertInboundRejection(UUID id, String peerId, String fromWallet, String toWallet, String reference,
                                       String payloadHash, String payloadJson, String status, String reason) {
        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, peer_vasp_id, originator_wallet, beneficiary_wallet,
               ivms101_payload, payload_hash, protocol_message_id, error_message, received_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,now(),now(),now())
            ON CONFLICT DO NOTHING
            """, id, "INBOUND", status, peerId, peerId, fromWallet, toWallet, payloadJson, payloadHash, reference,
                truncate(reason));
    }

    /**
     * Records a failed delivery attempt. {@code nextRetryAt} is null when the operation was refused to the
     * caller (the operator repeats it; no automatic re-delivery of a message about a transfer that did
     * not happen) and set for crash leftovers and queued retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID messageId, String errorMessage, Instant nextRetryAt) {
        jdbc.update("""
                UPDATE travel_rule_message
                SET status=?, error_message=?, attempts=attempts+1, next_retry_at=?, updated_at=now()
                WHERE id=? AND status IN (?,?)
                """, TravelRuleService.STATUS_FAILED, truncate(errorMessage),
                nextRetryAt == null ? null : java.sql.Timestamp.from(nextRetryAt), messageId,
                TravelRuleService.STATUS_PENDING_SEND, TravelRuleService.STATUS_FAILED);
    }

    public void markFailed(UUID messageId, String errorMessage) {
        markFailed(messageId, errorMessage, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID messageId, String protocolName, String protocolMessageId, String beneficiaryVaspId) {
        int updated = jdbc.update("""
            UPDATE travel_rule_message
            SET status=?, protocol=?, protocol_message_id=?, sent_at=now(), error_message=NULL,
                attempts=attempts+1, next_retry_at=NULL, updated_at=now()
            WHERE id=? AND status IN (?,?)
            """, TravelRuleService.STATUS_SENT, protocolName, protocolMessageId, messageId,
                TravelRuleService.STATUS_PENDING_SEND, TravelRuleService.STATUS_FAILED);
        if (updated == 1) {
            events.publishEvent(new TravelRuleMessageSentEvent(messageId, null, "SYSTEM", Map.of(
                    "protocol", protocolName != null ? protocolName : "",
                    "beneficiaryVaspId", beneficiaryVaspId != null ? beneficiaryVaspId : "")));
        }
    }

    /**
     * Crash recovery: PENDING_SEND rows older than {@code staleMinutes} (the JVM died between the insert
     * and the result) become FAILED, queued for re-delivery, with an alert each.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> sweepStalePending(int staleMinutes) {
        List<UUID> ids = jdbc.queryForList("""
            UPDATE travel_rule_message
            SET status=?, error_message='Send outcome unknown after restart/timeout (stale PENDING_SEND)',
                next_retry_at=now(), updated_at=now()
            WHERE direction='OUTBOUND' AND status=? AND updated_at < now() - make_interval(mins => ?)
            RETURNING id
            """, UUID.class, TravelRuleService.STATUS_FAILED, TravelRuleService.STATUS_PENDING_SEND, staleMinutes);
        for (UUID id : ids) {
            events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.DELIVERY_ALERT,
                    "TravelRuleMessage", id, null, "SYSTEM", Map.of("kind", "STALE_PENDING_SEND")));
        }
        return ids;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alertRetryExhausted(UUID messageId, int attempts) {
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.DELIVERY_ALERT,
                "TravelRuleMessage", messageId, null, "SYSTEM",
                Map.of("kind", "RETRY_EXHAUSTED", "attempts", attempts)));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
