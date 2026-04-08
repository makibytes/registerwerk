CREATE TABLE asset_holder (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id          UUID NOT NULL REFERENCES asset(id),
    investor_id       UUID NOT NULL REFERENCES legal_entity(id),
    wallet_address    VARCHAR(66) NOT NULL,
    whitelisted       BOOLEAN NOT NULL DEFAULT false,
    whitelist_tx_hash VARCHAR(66),
    nominal_amount    NUMERIC(38, 18) NOT NULL DEFAULT 0,
    acquisition_date  DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_holder_wallet ON asset_holder (asset_id, wallet_address);
CREATE INDEX idx_holder_investor       ON asset_holder (investor_id);
CREATE INDEX idx_holder_asset          ON asset_holder (asset_id);
