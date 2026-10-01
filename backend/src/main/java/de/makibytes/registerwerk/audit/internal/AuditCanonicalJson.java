package de.makibytes.registerwerk.audit.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Produces a deterministic JSON representation of an audit event's identity + payload,
 * used as the hash-chain input by both {@link AuditEventRecorder} (write path) and
 * {@link AuditChainVerificationService} (verify path). Both MUST use this exact class so
 * a row written today can be re-verified byte-for-byte months later.
 *
 * <p>Determinism is achieved by recursively re-keying every {@link Map} into a
 * {@link TreeMap} (alphabetical key order) before serialization, rather than relying on
 * a Jackson feature flag — this is stable across Jackson versions and independent of the
 * original insertion order of the event's payload map. List order is preserved as-is,
 * since payload lists in this codebase are already order-significant (e.g. batch
 * from/to arrays).
 */
@Component
class AuditCanonicalJson {

    private final ObjectMapper mapper;

    AuditCanonicalJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Canonical JSON string for {@code eventType/subjectType/subjectId/payload}. */
    String canonicalize(String eventType, String subjectType, UUID subjectId, Map<String, Object> payload) {
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("eventType", eventType);
        envelope.put("subjectType", subjectType);
        envelope.put("subjectId", subjectId != null ? subjectId.toString() : null);
        envelope.put("payload", sortKeys(payload != null ? payload : Map.of()));
        try {
            return mapper.writeValueAsString(envelope);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to canonicalize audit event payload for hashing", e);
        }
    }

    /**
     * Canonical JSON for a stored row, selected by its {@code canon_version} column. Version 1 is
     * the legacy envelope (identity + payload only); version 2 additionally covers actor id/role,
     * event time (epoch micros as a decimal string), correlation id, reversal link and the version
     * itself. An unknown version is an error (the verifier reports it as a broken chain).
     */
    String canonicalize(AuditEvent e) {
        int version = e.getCanonVersion();
        if (version == 1) {
            return canonicalize(e.getEventType(), e.getSubjectType(), e.getSubjectId(), e.getPayload());
        }
        if (version != 2) {
            throw new IllegalArgumentException("Unknown audit canon_version " + version);
        }
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("canonVersion", 2);
        envelope.put("eventType", e.getEventType());
        envelope.put("subjectType", e.getSubjectType());
        envelope.put("subjectId", e.getSubjectId() != null ? e.getSubjectId().toString() : null);
        envelope.put("actorId", e.getActorId() != null ? e.getActorId().toString() : null);
        envelope.put("actorRole", e.getActorRole());
        envelope.put("occurredAtMicros", Long.toString(epochMicros(e.getOccurredAt())));
        envelope.put("correlationId", e.getCorrelationId() != null ? e.getCorrelationId().toString() : null);
        envelope.put("reversesEventId", e.getReversesEventId() != null ? e.getReversesEventId().toString() : null);
        envelope.put("payload", sortKeys(e.getPayload() != null ? e.getPayload() : Map.of()));
        try {
            return mapper.writeValueAsString(envelope);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to canonicalize audit event payload for hashing", ex);
        }
    }

    static long epochMicros(java.time.Instant t) {
        return java.time.temporal.ChronoUnit.MICROS.between(java.time.Instant.EPOCH, t);
    }

    private static Object sortKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditCanonicalJson::sortKeys).toList();
        }
        return value;
    }
}
