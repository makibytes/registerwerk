-- V5: Register inspection (§10 eWpG / §10 eWpRV) and register transfer
-- documentation (§§21/22 eWpG, §20 eWpRV).
--
-- §10 eWpG grants electronic inspection rights to participants: the issuer, the
-- holder, and — in single entry — anyone in whose favour a right is recorded. A
-- Berechtigter always has a legitimate interest (§10(2) eWpRV). Other applicants
-- must demonstrate a legitimate interest, which the operator reviews.
--
-- §§21/22 eWpG require the operator to be able to hand the register over to a
-- successor (e.g. when it can no longer meet the statutory requirements). §20
-- eWpRV requires the procedure and the data transfer to be documented. The
-- on-chain control handover already exists in the contracts; this records the
-- off-chain export and its status.

-- ── Register inspection requests (§10 eWpG) ───────────────────────────────────
CREATE TABLE register_inspection_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    -- The applicant; may be an onboarded entity or an external party.
    requester_entity_id UUID REFERENCES legal_entity(id),
    requester_name      TEXT NOT NULL,
    requester_email     TEXT,
    -- Asserted basis: ISSUER, HOLDER, BENEFICIARY (always legitimate, §10(2)
    -- eWpRV) or LEGITIMATE_INTEREST (reviewed by the operator).
    legal_basis         VARCHAR(30) NOT NULL,
    stated_interest     TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    -- REQUESTED, APPROVED, REJECTED, FULFILLED.
    decision_reason     TEXT,
    decided_by          UUID REFERENCES legal_entity(id),
    decided_at          TIMESTAMPTZ,
    fulfilled_at        TIMESTAMPTZ,
    content_hash        VARCHAR(66),   -- hash of the disclosed extract, when fulfilled
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_asset ON register_inspection_request (asset_id);
CREATE INDEX idx_inspection_status ON register_inspection_request (status)
    WHERE status IN ('REQUESTED', 'APPROVED');

-- ── Register transfer (§§21/22 eWpG, §20 eWpRV) ───────────────────────────────
CREATE TABLE register_transfer (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    -- Name / identifier of the successor registry operator.
    successor_name        TEXT NOT NULL,
    successor_identifier  TEXT,
    -- Reason for the handover (§22: e.g. operator can no longer meet requirements).
    reason                TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, EXPORTED, HANDED_OVER, COMPLETED, CANCELLED.
    -- The exported data package (the §20 eWpRV data transfer) and its hash.
    export_hash           VARCHAR(66),
    export_manifest       JSONB,
    -- On-chain control handover reference (links to the contract two-step handover).
    onchain_tx_hash       VARCHAR(66),
    initiated_by          UUID REFERENCES legal_entity(id),
    initiated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at           TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_register_transfer_asset ON register_transfer (asset_id);
CREATE INDEX idx_register_transfer_status ON register_transfer (status)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');
