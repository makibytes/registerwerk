package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Economic terms of an approved/issued asset were amended under 4-eyes control. The audit entry
 * carries every changed field's before and after value plus the stated legal basis, so the
 * tamper-evident log alone reconstructs what the terms were at any point.
 *
 * @param before changed fields → previous value (string form; null when previously unset)
 * @param after  changed fields → new value
 */
public record AssetTermsAmendedEvent(UUID assetId, UUID actorId, String actorRole, UUID dualControlApproverId,
                                     String legalReference, Map<String, String> before,
                                     Map<String, String> after) implements AuditableEvent {
    public String eventType()   { return "ASSET_TERMS_AMENDED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legalReference", legalReference);
        payload.put("before", before);
        payload.put("after", after);
        return payload;
    }
}
