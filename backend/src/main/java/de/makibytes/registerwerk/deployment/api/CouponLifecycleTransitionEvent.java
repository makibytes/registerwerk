package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A coupon payment's status really changed by the system ({@code COUPON_OVERDUE}, {@code COUPON_MISSED}, derived from
 * the target state); never published when the status stays as it was (9A-04R). System-attributed.
 */
public record CouponLifecycleTransitionEvent(UUID couponPaymentId, UUID assetId, CouponStatus from, CouponStatus to,
                                             String cause, UUID corporateActionId, Integer graceDays)
        implements AuditableEvent {

    @Override public String eventType()   { return "COUPON_" + to; }
    @Override public String subjectType() { return "Asset"; }
    @Override public UUID   subjectId()   { return assetId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("couponPaymentId", String.valueOf(couponPaymentId));
        payload.put("from", String.valueOf(from));
        payload.put("to", String.valueOf(to));
        payload.put("cause", cause);
        payload.put("corporateActionId", String.valueOf(corporateActionId));
        if (graceDays != null) {
            payload.put("graceDays", graceDays);
        }
        return payload;
    }
}
