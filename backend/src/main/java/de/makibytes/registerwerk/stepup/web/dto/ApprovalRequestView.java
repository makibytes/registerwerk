package de.makibytes.registerwerk.stepup.web.dto;

import de.makibytes.registerwerk.stepup.internal.ApprovalRequestRecord;

import java.time.Instant;
import java.util.UUID;

/**
 * One approval request as shown to the requester and to eligible approvers. {@code canonicalBody} is the
 * canonical JSON the approval is bound to ({@code ""} = the request has no body, null = the body is not part of
 * this action's approval). {@code targetDigest} is the digest the minted token will carry.
 */
public record ApprovalRequestView(
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

    public static ApprovalRequestView from(ApprovalRequestRecord r) {
        return new ApprovalRequestView(r.id(), r.requesterUserId(), r.requesterEmail(), r.action(), r.method(),
                r.path(), r.query(), r.canonicalBody(), r.targetDigest(), r.status(), r.approverUserId(),
                r.approverEmail(), r.createdAt(), r.expiresAt(), r.decidedAt(), r.decisionNote(), r.claimedAt());
    }
}
