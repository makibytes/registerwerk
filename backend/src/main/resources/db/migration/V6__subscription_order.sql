-- Primary-market subscription/allocation flow (F-BLOCKER-3): previously the only way to create
-- a position was an issuer manually typing a wallet address into a dialog — no order, no
-- allocation, no investor confirmation.
CREATE TABLE subscription_order (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    investor_entity_id  UUID NOT NULL,
    wallet_address      TEXT NOT NULL,
    requested_amount    NUMERIC(38,18) NOT NULL,
    allocated_amount    NUMERIC(38,18),
    status              VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_at        TIMESTAMPTZ,
    allocated_by        UUID,
    confirmed_at        TIMESTAMPTZ,
    resulting_holder_id UUID,
    rejection_reason    TEXT
);

CREATE INDEX idx_subscription_order_asset ON subscription_order (asset_id);
CREATE INDEX idx_subscription_order_investor ON subscription_order (investor_entity_id);
CREATE INDEX idx_subscription_order_status ON subscription_order (asset_id, status);
