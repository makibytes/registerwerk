-- T3-08 primary subscription: accept -> payment confirmed -> settled flow, allocation expiry.
-- T3-13 issuer edits of register entries: change requests + an audit row per executed change.

ALTER TABLE asset ADD COLUMN subscription_payment_window_bd INT NOT NULL DEFAULT 10;

ALTER TABLE subscription_order ADD COLUMN version                BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscription_order ADD COLUMN accepted_at            TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN allocation_expires_at  TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN amount_due             NUMERIC(38,18);
ALTER TABLE subscription_order ADD COLUMN payment_currency       VARCHAR(10);
ALTER TABLE subscription_order ADD COLUMN paid_amount            NUMERIC(38,18);
ALTER TABLE subscription_order ADD COLUMN refund_due             NUMERIC(38,18);
ALTER TABLE subscription_order ADD COLUMN payment_reference      TEXT;
ALTER TABLE subscription_order ADD COLUMN payment_value_date     DATE;
ALTER TABLE subscription_order ADD COLUMN payment_confirmed_at   TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN payment_confirmed_by   UUID;
ALTER TABLE subscription_order ADD COLUMN settled_at             TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN settlement_tx_id       UUID;
ALTER TABLE subscription_order ADD COLUMN lapsed_at              TIMESTAMPTZ;
ALTER TABLE subscription_order ADD COLUMN release_reason         TEXT;

-- Orders that are ALLOCATED today never had a deadline: give them one from the deploy date
-- (the calendar-day stand-in for the 10 TARGET business days a fresh allocation gets).
UPDATE subscription_order
   SET allocation_expires_at = now() + INTERVAL '14 days'
 WHERE status = 'ALLOCATED';
-- CONFIRMED orders stay CONFIRMED (legacy, read-only); their register entry was created by the
-- old confirm step and is not touched.

CREATE INDEX idx_subscription_order_expiry ON subscription_order (allocation_expires_at)
    WHERE status = 'ALLOCATED';

-- Light table on which an issuer can only ASK for a register-entry change; the operator executes
-- (or rejects) it against the instruction the issuer relays.
CREATE TABLE holder_change_request (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    request_type          VARCHAR(30) NOT NULL,
    holder_id             UUID,
    payload               JSONB NOT NULL DEFAULT '{}'::jsonb,
    instructing_party     VARCHAR(40) NOT NULL,
    instruction_reference TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    requested_by          UUID,
    requested_by_role     VARCHAR(40),
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by            UUID,
    decided_at            TIMESTAMPTZ,
    decision_reason       TEXT,
    resulting_change_id   UUID,
    CONSTRAINT chk_holder_change_request_type CHECK (request_type IN ('ADD_HOLDER','UPDATE_ATTRIBUTES')),
    CONSTRAINT chk_holder_change_request_status CHECK (status IN ('REQUESTED','EXECUTED','REJECTED'))
);
CREATE INDEX idx_holder_change_request_asset ON holder_change_request (asset_id, status);

-- One row per executed register-entry change: who instructed it, on what reference, before/after.
CREATE TABLE asset_holder_change (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    holder_id             UUID NOT NULL,
    change_type           VARCHAR(30) NOT NULL,
    instructing_party     VARCHAR(40) NOT NULL,
    instruction_reference TEXT NOT NULL,
    before_state          JSONB,
    after_state           JSONB,
    actor_id              UUID,
    actor_role            VARCHAR(40),
    approver_id           UUID,
    change_request_id     UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_asset_holder_change_holder ON asset_holder_change (holder_id);
CREATE INDEX idx_asset_holder_change_asset ON asset_holder_change (asset_id, created_at);
