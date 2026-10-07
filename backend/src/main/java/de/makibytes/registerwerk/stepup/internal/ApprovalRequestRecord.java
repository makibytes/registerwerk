package de.makibytes.registerwerk.stepup.internal;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code approval_request}, with the requester's and approver's e-mail for display. */
public record ApprovalRequestRecord(
        UUID id,
        UUID requesterUserId,
        String requesterEmail,
        String action,
        String method,
        String path,
        String query,
        String canonicalBody,
        String targetDigest,
        String status,
        UUID approverUserId,
        String approverEmail,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionNote,
        Instant claimedAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    public static final String CLAIMED = "CLAIMED";

    /** {@code "METHOD /path[?query]"} - the target string an approver's client digests. */
    public String target() {
        return method + " " + path + (query == null || query.isEmpty() ? "" : "?" + query);
    }
}
