package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Persists a poison lifecycle envelope after its business transaction has rolled back. */
@Service
class ChaincacheLifecycleFailureRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    ChaincacheLifecycleFailureRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID chainConfigId, String consumerId, String expectedChainKey,
            String expectedDomain, JsonNode event, RuntimeException failure) {
        String eventId = event.path("eventId").asText(null);
        JsonNode sequence = event.get("sequence");
        if (eventId == null || eventId.isBlank() || sequence == null || !sequence.isIntegralNumber()
                || !sequence.canConvertToLong() || sequence.asLong() < 0) {
            // There is no safe immutable transport identity under which this malformed frame can
            // be journalled. The connection still fail-stops and its cursor remains unchanged.
            return;
        }
        String domain = event.path("durabilityDomainId").asText(expectedDomain);
        String chainKey = event.path("chainKey").asText(expectedChainKey);
        String schema = event.path("schemaVersion").asText("UNKNOWN");
        String kind = event.path("kind").asText("UNKNOWN");
        String finality = event.path("finality").asText(null);
        if (finality != null) {
            try {
                FinalityLevel.valueOf(finality);
            } catch (IllegalArgumentException e) {
                finality = null;
            }
        }
        String raw = objectMapper.writeValueAsString(event);
        String hash = ChaincacheLifecycleEventProcessor.payloadHash(objectMapper, event);
        String reason = failure.getClass().getSimpleName() + ": "
                + String.valueOf(failure.getMessage());

        jdbcTemplate.update("""
                INSERT INTO chaincache_event_inbox
                  (durability_domain_id, chain_config_id, chain_key, source_sequence, event_id,
                   schema_version, event_kind, finality, payload_hash, raw_event,
                   processing_state, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), 'QUARANTINED', ?)
                ON CONFLICT (durability_domain_id, chain_config_id, event_id) DO UPDATE
                  SET processing_state = 'QUARANTINED', last_error = EXCLUDED.last_error,
                      last_received_at = NOW(), delivery_count = chaincache_event_inbox.delivery_count + 1
                """, domain, chainConfigId, chainKey, sequence.asLong(), eventId, schema, kind,
                finality, hash, raw, reason);
        ChaincacheSubscriptionSql.quarantineSubscription(
                jdbcTemplate, domain, chainConfigId, chainKey, consumerId);
    }
}
