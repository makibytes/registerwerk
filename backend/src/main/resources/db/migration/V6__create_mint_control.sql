CREATE TABLE mint_control_rule (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    target_address       VARCHAR(66) NOT NULL,
    rule_type            VARCHAR(30) NOT NULL,
    max_amount           NUMERIC(38, 18),
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_rule_type CHECK (
        rule_type IN ('MINT_ALLOWANCE','AUTO_APPROVE_TRANSFER','AUTO_APPROVE_BURN')
    )
);

CREATE INDEX idx_mint_rule_deployment ON mint_control_rule (asset_deployment_id);
CREATE INDEX idx_mint_rule_target     ON mint_control_rule (target_address);
