-- Phase 5 / K3: repo desk controls (5A-07, 5A-08, 5A-09, 5B-06, 5C-05 interim).
--   * versioned, immutable quotes (a re-quote is a new row; the old one becomes SUPERSEDED)
--   * trade terms snapshot (terms_hash, accepted_quote_version, day-count basis) and SFTR record-keeping fields
--   * bilateral default mechanics: payer-side declarations, margin-call valuation snapshot, default notice
--     + grace, defaulting party, DISPUTED state with operator resolution
--   * substitution requests in their own table with a settlement sub-state
--   * opt-in participant directory
-- Existing rows stay honest: new trade columns are nullable or defaulted so legacy trades keep working.

-- ── quotes ──────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE repo_quote ADD COLUMN quote_version INTEGER NOT NULL DEFAULT 1;

-- One row per submission: the per-counterparty uniqueness constraint moves to "one ACTIVE quote".
ALTER TABLE repo_quote DROP CONSTRAINT uq_repo_quote_counterparty;
ALTER TABLE repo_quote DROP CONSTRAINT ck_repo_quote_status;
ALTER TABLE repo_quote ADD CONSTRAINT ck_repo_quote_status
    CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED'));
CREATE UNIQUE INDEX uq_repo_quote_active_per_counterparty
    ON repo_quote(rfq_id, quoting_entity_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_repo_quote_version
    ON repo_quote(rfq_id, quoting_entity_id, quote_version);

-- ── trades ──────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE repo_trade
    ADD COLUMN terms_hash VARCHAR(64),
    ADD COLUMN accepted_quote_version INTEGER,
    ADD COLUMN day_count_basis INTEGER NOT NULL DEFAULT 360,
    ADD COLUMN uti VARCHAR(52),
    ADD COLUMN venue VARCHAR(40) NOT NULL DEFAULT 'BILATERAL_UNREGULATED',
    ADD COLUMN collateral_reuse_consent BOOLEAN NOT NULL DEFAULT false,
    -- payer-side declarations (the payer says "sent"; the receiver confirms or disputes)
    ADD COLUMN open_cash_declared_at TIMESTAMPTZ,
    ADD COLUMN open_collateral_declared_at TIMESTAMPTZ,
    ADD COLUMN close_cash_declared_at TIMESTAMPTZ,
    ADD COLUMN close_collateral_declared_at TIMESTAMPTZ,
    -- margin call snapshot
    ADD COLUMN margin_valuation_reference VARCHAR(200),
    ADD COLUMN margin_valuation_amount NUMERIC(38,18),
    ADD COLUMN margin_haircut_bps INTEGER,
    ADD COLUMN margin_delivered_at TIMESTAMPTZ,
    ADD COLUMN margin_delivered_reference VARCHAR(200),
    -- default: notice, grace, direction
    ADD COLUMN default_notice_at TIMESTAMPTZ,
    ADD COLUMN default_notice_by UUID REFERENCES legal_entity(id),
    ADD COLUMN default_notice_ground VARCHAR(30),
    ADD COLUMN defaulting_party_entity_id UUID REFERENCES legal_entity(id),
    ADD COLUMN default_ground VARCHAR(30),
    -- dispute
    ADD COLUMN dispute_reason VARCHAR(1000),
    ADD COLUMN pre_dispute_status VARCHAR(30),
    ADD COLUMN disputed_at TIMESTAMPTZ,
    ADD COLUMN disputed_by UUID REFERENCES legal_entity(id);

ALTER TABLE repo_trade DROP CONSTRAINT ck_repo_trade_status;
ALTER TABLE repo_trade ADD CONSTRAINT ck_repo_trade_status CHECK (status IN (
    'PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'DISPUTED', 'CLOSED', 'DEFAULTED', 'CANCELLED'));
ALTER TABLE repo_trade ADD CONSTRAINT ck_repo_trade_day_count CHECK (day_count_basis IN (360, 365));
ALTER TABLE repo_trade ADD CONSTRAINT ck_repo_trade_pre_dispute CHECK (
    pre_dispute_status IS NULL OR pre_dispute_status IN
        ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE'));
CREATE UNIQUE INDEX uq_repo_trade_uti ON repo_trade(uti) WHERE uti IS NOT NULL;
-- encumbrance / redemption-blocker lookups: open trades per collateral asset and per borrower
CREATE INDEX idx_repo_trade_open_collateral ON repo_trade(collateral_asset_id, cash_borrower_entity_id)
    WHERE status IN ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'DISPUTED');

-- Lifecycle events by the platform operator (dispute resolution) or the system (corporate-action notice)
-- have no acting company.
ALTER TABLE repo_lifecycle_event ALTER COLUMN actor_entity_id DROP NOT NULL;

-- ── substitution requests ───────────────────────────────────────────────────────────────────────
-- PENDING -> APPROVED (replacement/return legs outstanding) -> COMPLETED, or REJECTED / WITHDRAWN / EXPIRED.
CREATE TABLE repo_substitution_request (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    repo_trade_id UUID NOT NULL REFERENCES repo_trade(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES asset(id),
    quantity NUMERIC(38,18) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_by UUID NOT NULL REFERENCES legal_entity(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by UUID REFERENCES legal_entity(id),
    decided_at TIMESTAMPTZ,
    replacement_received_at TIMESTAMPTZ,
    original_returned_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    note VARCHAR(1000),
    CONSTRAINT ck_repo_substitution_status
        CHECK (status IN ('PENDING', 'APPROVED', 'COMPLETED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_repo_substitution_quantity CHECK (quantity > 0)
);
CREATE INDEX idx_repo_substitution_trade ON repo_substitution_request(repo_trade_id, requested_at);
-- at most one live (pending or approved-but-unsettled) substitution per trade
CREATE UNIQUE INDEX uq_repo_substitution_live ON repo_substitution_request(repo_trade_id)
    WHERE status IN ('PENDING', 'APPROVED');

-- carry over substitutions that were pending on the old columns
INSERT INTO repo_substitution_request (id, repo_trade_id, asset_id, quantity, status, requested_by, requested_at)
SELECT gen_random_uuid(), t.id, t.pending_substitution_asset_id, t.pending_substitution_quantity, 'PENDING',
       COALESCE(t.substitution_requested_by, t.cash_borrower_entity_id), now()
FROM repo_trade t
WHERE t.pending_substitution_asset_id IS NOT NULL AND t.pending_substitution_quantity IS NOT NULL
  AND t.status = 'OPEN';

-- ── participant directory (opt-in, T5-07) ───────────────────────────────────────────────────────
-- Existing companies are NOT opted in (no backfill): the desk is release-gated and participation is a
-- deliberate act of a company administrator.
CREATE TABLE repo_desk_participant (
    entity_id UUID PRIMARY KEY REFERENCES legal_entity(id),
    opted_in_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    opted_in_by UUID,
    listed BOOLEAN NOT NULL DEFAULT false,
    opted_out_at TIMESTAMPTZ
);
