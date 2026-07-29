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
