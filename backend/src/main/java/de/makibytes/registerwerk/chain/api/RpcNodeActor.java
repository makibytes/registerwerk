package de.makibytes.registerwerk.chain.api;

import java.util.UUID;

/**
 * Who is changing RPC node governance state (P4C-1): the acting operator, and the second approver
 * validated by the step-up aspect ({@code requireSecondApprover = true}). Passed explicitly into
 * {@code RpcNodeService} so the audit event carries both identities.
 */
public record RpcNodeActor(UUID actorId, String actorRole, UUID approverId, UUID requestId) {

    /** For system-initiated changes (no HTTP request, no approver). */
    public static RpcNodeActor system() {
        return new RpcNodeActor(null, "SYSTEM", null, null);
    }
}
