package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One or more entitlements of a corporate action were NOT released for payout because the holder failed the
 * party-eligibility gate at payout time (Wave 0b H6): entity not ACTIVE, KYC missing or expired, unresolved sanctions
 * hit, active Sperrvermerk. The other holders are paid; each held entry keeps its entitlement and the reason, and an
 * operator task is raised for the holder. System-attributed.
 *
 * @param held one map per held entry: {@code assetHolderId}, {@code investorId}, {@code walletAddress},
 *             {@code entitlementAmount}, {@code reason}
 */
public record CorporateActionPayoutHeldEvent(UUID corporateActionId, UUID assetId, List<Map<String, Object>> held)
        implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_PAYOUT_HELD"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() {
        return Map.of("assetId", String.valueOf(assetId), "heldCount", held.size(), "held", held);
    }
}
