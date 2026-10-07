-- T8-02 / Wave 3a-1: the in-app approval queue. An initiator files one concrete request (action, method, path,
-- query, canonical body); an eligible approver (REGISTRY_ADMIN / COMPLIANCE_OFFICER, never the requester) decides
-- it in the app; the requester then claims a server-minted, single-use dual-control approver token that is bound
-- to the exact request digest (the same digest StepUpTokenValidator checks on the real endpoint).
--
-- The state machine is enforced by atomic conditional UPDATEs in ApprovalRequestRepository; the constraints below
-- are the last line of defence if code ever gets it wrong.

CREATE TABLE approval_request (
    id                UUID          PRIMARY KEY,
    requester_user_id UUID          NOT NULL,
    action            VARCHAR(160)  NOT NULL,
    method            VARCHAR(8)    NOT NULL,
    path              VARCHAR(500)  NOT NULL,
    query             VARCHAR(2000),
    -- Canonical JSON of the request body (DualControlTarget.canonicalJson); NULL for reasons whose body is not
    -- bound (secret-bearing or non-JSON payloads are never copied into an approval request).
    canonical_body    TEXT,
    target_digest     VARCHAR(64)   NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    approver_user_id  UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ   NOT NULL,
    decided_at        TIMESTAMPTZ,
    decision_note     VARCHAR(1000),
    claimed_at        TIMESTAMPTZ,
    claim_jti         VARCHAR(64),
    CONSTRAINT approval_request_status_chk
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'CLAIMED')),
    -- Self-approval is impossible at the storage level, whatever the application does.
    CONSTRAINT approval_request_no_self_approval_chk
        CHECK (approver_user_id IS NULL OR approver_user_id <> requester_user_id),
    CONSTRAINT approval_request_decided_chk
        CHECK (status = 'PENDING' OR status IN ('EXPIRED', 'CANCELLED') OR approver_user_id IS NOT NULL),
    CONSTRAINT approval_request_claimed_chk
        CHECK ((status = 'CLAIMED') = (claim_jti IS NOT NULL AND claimed_at IS NOT NULL))
);

COMMENT ON TABLE approval_request IS
    'In-app dual-control approval queue (T8-02). One row = one concrete request; the approver token is minted only '
    'when the requester claims an APPROVED row, and is bound to target_digest.';

CREATE INDEX approval_request_pending_idx ON approval_request (created_at) WHERE status = 'PENDING';
CREATE INDEX approval_request_requester_idx ON approval_request (requester_user_id, created_at DESC);
CREATE INDEX approval_request_expiry_idx ON approval_request (expires_at) WHERE status IN ('PENDING', 'APPROVED');
