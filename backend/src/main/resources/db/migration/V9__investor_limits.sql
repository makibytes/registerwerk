-- Per-investor eligibility limits (F-BLOCKER-12): previously the only limits were ERC-3643
-- on-chain compliance modules, surfaced read-only and token-wide — no per-investor minimum
-- subscription, maximum holding, or lockup existed anywhere.

ALTER TABLE asset ADD COLUMN min_investment_amount NUMERIC(38, 8);
ALTER TABLE asset ADD COLUMN max_holding_amount NUMERIC(38, 8);

CREATE TABLE investor_limit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                  UUID NOT NULL REFERENCES asset(id),
    investor_entity_id        UUID NOT NULL,
    min_investment_override   NUMERIC(38, 8),
    max_holding_override      NUMERIC(38, 8),
    lockup_until              DATE,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                UUID,
    CONSTRAINT uq_investor_limit_asset_investor UNIQUE (asset_id, investor_entity_id)
);
