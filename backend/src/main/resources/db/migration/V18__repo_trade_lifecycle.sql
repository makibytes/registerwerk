CREATE TABLE repo_trade (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL UNIQUE REFERENCES repo_rfq(id),
    accepted_quote_id UUID NOT NULL UNIQUE REFERENCES repo_quote(id),
    cash_borrower_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    cash_lender_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    collateral_quantity NUMERIC(38,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency CHAR(3) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    repurchase_amount NUMERIC(38,18) NOT NULL,
    settlement_method VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    open_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    open_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    margin_call_amount NUMERIC(38,18),
    margin_call_due_at TIMESTAMPTZ,
    pending_substitution_asset_id UUID REFERENCES asset(id),
    pending_substitution_quantity NUMERIC(38,18),
    substitution_requested_by UUID REFERENCES legal_entity(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_repo_trade_status CHECK (status IN ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'CLOSED', 'DEFAULTED', 'CANCELLED')),
    CONSTRAINT ck_repo_trade_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_trade_cash CHECK (cash_amount > 0 AND repurchase_amount >= cash_amount),
    CONSTRAINT ck_repo_trade_parties CHECK (cash_borrower_entity_id <> cash_lender_entity_id),
    CONSTRAINT ck_repo_trade_haircut CHECK (haircut_bps BETWEEN 0 AND 10000)
);

CREATE TABLE repo_lifecycle_event (
    id UUID PRIMARY KEY,
    repo_trade_id UUID NOT NULL REFERENCES repo_trade(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    actor_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    actor_user_id UUID,
    amount NUMERIC(38,18),
    asset_id UUID REFERENCES asset(id),
    quantity NUMERIC(38,18),
    reference VARCHAR(200),
    note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_repo_trade_party_borrower ON repo_trade(cash_borrower_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_party_lender ON repo_trade(cash_lender_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_status ON repo_trade(status, end_date);
CREATE INDEX idx_repo_event_trade ON repo_lifecycle_event(repo_trade_id, created_at);

