package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.finality.api.ChaincacheInboxRecoveryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implements {@link ChaincacheInboxRecoveryPort} — the {@code finality} module's inverted-dependency
 * hook for clearing this module's own independent quarantine state on the Chaincache lifecycle
 * inbox and subscription cursor. See that interface's javadoc for why the two quarantine states
 * (chain-level, inbox-level) need to be resolved together.
 */
@Service
class ChaincacheInboxRecoveryService implements ChaincacheInboxRecoveryPort {

    private final JdbcTemplate jdbcTemplate;

    ChaincacheInboxRecoveryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int clearQuarantinedInbox(UUID chainConfigId) {
        int inboxCleared = jdbcTemplate.update("""
                UPDATE chaincache_event_inbox
                   SET processing_state = 'PROCESSED', last_error = NULL
                 WHERE chain_config_id = ? AND processing_state = 'QUARANTINED'
                """, chainConfigId);
        // A quarantined inbox row was, in both quarantine paths in ChaincacheLifecycleEventProcessor
        // (a sequence gap/regression, or an eventId reused with a different payload), quarantined
        // *before* apply() ever ran for it — its business effect was never actually applied. It is
        // still marked PROCESSED here rather than deleted or reset to RECEIVED, and this is safe
        // specifically because chain_contract_subscription.last_sequence is deliberately left
        // untouched below (still one behind this event): when chaincache redelivers the same
        // never-acknowledged sequence after reconnect, process()'s contiguity check
        // (last_sequence + 1 == event.sequence()) now passes cleanly (the false "gap" is gone),
        // its redelivery short-circuit does *not* fire (that requires an exact match against
        // cursor.lastSequence, which no longer holds), so it falls through to a genuine apply()
        // call — the business effect is applied for the first time here, and only then does the
        // ordinary flow advance the cursor and reconfirm PROCESSED. If the original quarantine was
        // instead a genuine payload-hash mismatch (chaincache reusing an eventId for different
        // content), redelivery of the same immutable event still matches the stored hash and
        // proceeds identically; a *new* mismatch on redelivery re-quarantines correctly.
        jdbcTemplate.update("""
                UPDATE chain_contract_subscription
                   SET subscription_state = 'LIVE', updated_at = NOW()
                 WHERE chain_config_id = ? AND subscription_state = 'QUARANTINED'
                """, chainConfigId);
        return inboxCleared;
    }

    @Override
    public List<QuarantinedInboxEvent> listQuarantinedInbox(UUID chainConfigId) {
        return jdbcTemplate.query("""
                SELECT event_id, event_kind, finality, last_error, delivery_count, last_received_at
                  FROM chaincache_event_inbox
                 WHERE chain_config_id = ? AND processing_state = 'QUARANTINED'
                 ORDER BY last_received_at DESC
                """, (rs, row) -> new QuarantinedInboxEvent(
                        rs.getString("event_id"), rs.getString("event_kind"), rs.getString("finality"),
                        rs.getString("last_error"), rs.getLong("delivery_count"),
                        rs.getTimestamp("last_received_at").toInstant()),
                chainConfigId);
    }
}
