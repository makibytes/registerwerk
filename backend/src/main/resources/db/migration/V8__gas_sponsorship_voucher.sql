-- EwpgPaymaster vouchers (review phase 2, T2-01/T2-02): every sponsored UserOperation needs a
-- voucher signed by the backend. Each issued voucher is recorded with its worst-case prefund so
-- the policy's monthly_cap_eth is enforced (it previously had no effect anywhere).
CREATE TABLE gas_sponsorship_voucher (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id            UUID NOT NULL REFERENCES gas_sponsorship_policy(id),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    entity_id            UUID NOT NULL,
    sender               VARCHAR(42) NOT NULL,
    chain_id             BIGINT NOT NULL,
    user_op_nonce        NUMERIC(78,0) NOT NULL,
    max_cost_wei         NUMERIC(78,0) NOT NULL CHECK (max_cost_wei >= 0),
    valid_until          TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID
);

CREATE INDEX idx_gas_sponsorship_voucher_policy_created ON gas_sponsorship_voucher (policy_id, created_at);
