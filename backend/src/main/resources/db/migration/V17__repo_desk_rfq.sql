CREATE TABLE repo_rfq (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    requester_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    requester_user_id UUID,
    side VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    collateral_quantity NUMERIC(38,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency CHAR(3) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    proposed_repo_rate NUMERIC(12,8),
    proposed_haircut_bps INTEGER,
    settlement_method VARCHAR(20) NOT NULL DEFAULT 'DVP',
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_repo_rfq_side CHECK (side IN ('BORROW_CASH', 'LEND_CASH')),
    CONSTRAINT ck_repo_rfq_visibility CHECK (visibility IN ('TARGETED', 'BROADCAST')),
    CONSTRAINT ck_repo_rfq_status CHECK (status IN ('OPEN', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_repo_rfq_settlement CHECK (settlement_method IN ('DVP', 'FOP')),
    CONSTRAINT ck_repo_rfq_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_rfq_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_rfq_dates CHECK (end_date > start_date),
    CONSTRAINT ck_repo_rfq_haircut CHECK (proposed_haircut_bps IS NULL OR proposed_haircut_bps BETWEEN 0 AND 10000)
);

CREATE TABLE repo_rfq_target (
    repo_rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    PRIMARY KEY (repo_rfq_id, target_entity_id)
);

CREATE TABLE repo_quote (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    quoting_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    quoting_user_id UUID,
    cash_amount NUMERIC(38,18) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_repo_quote_counterparty UNIQUE (rfq_id, quoting_entity_id),
    CONSTRAINT ck_repo_quote_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_quote_rate CHECK (repo_rate >= 0),
    CONSTRAINT ck_repo_quote_haircut CHECK (haircut_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_repo_quote_status CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'))
);

CREATE INDEX idx_repo_rfq_requester ON repo_rfq(requester_entity_id, created_at DESC);
CREATE INDEX idx_repo_rfq_open ON repo_rfq(status, expires_at);
CREATE INDEX idx_repo_rfq_target_entity ON repo_rfq_target(target_entity_id, repo_rfq_id);
CREATE INDEX idx_repo_quote_rfq ON repo_quote(rfq_id, created_at DESC);
CREATE INDEX idx_repo_quote_entity ON repo_quote(quoting_entity_id, created_at DESC);

