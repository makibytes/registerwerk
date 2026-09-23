package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A gas-sponsorship policy was deactivated. The voucher issuer refuses the policy from this
 * moment; the on-chain {@code EwpgPaymaster.setPolicyActive(policyId, false)} is a separate
 * operator/funder action (the event is the trigger for it).
 */
public record GasSponsorshipPolicyDeactivatedEvent(
        UUID policyId, UUID deploymentId, UUID issuerId, UUID actorId, String actorRole
) implements AuditableEvent {
    public String eventType()   { return "GAS_SPONSORSHIP_POLICY_DEACTIVATED"; }
    public String subjectType() { return "GasSponsorshipPolicy"; }
    public UUID   subjectId()   { return policyId; }
    public Map<String, Object> payload() {
        Map<String, Object> payload = new HashMap<>();
        if (deploymentId != null) payload.put("deploymentId", deploymentId.toString());
        if (issuerId != null) payload.put("issuerId", issuerId.toString());
        return payload;
    }
}
