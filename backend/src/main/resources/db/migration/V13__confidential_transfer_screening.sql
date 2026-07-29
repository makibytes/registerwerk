-- Finding #8, Phase 9: a per-deployment sync cursor for ConfidentialTravelRuleScreeningService —
-- tracks the last indexed block number screened for Travel Rule obligations so each scheduled
-- run only decrypts and evaluates confidential transfer/mint events new since the previous run.
CREATE TABLE confidential_transfer_screening_state (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    last_screened_block  BIGINT NOT NULL DEFAULT 0,
    last_run_at          TIMESTAMPTZ,
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_conf_transfer_screening_deployment UNIQUE (asset_deployment_id)
);
