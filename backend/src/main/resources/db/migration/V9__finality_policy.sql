-- The finality policy model: which FinalityLevel a GatedOperation requires before it is allowed
-- to proceed. Resolution chain (most specific wins, see FinalityPolicyResolverImpl): asset
-- override > asset-scoped assignment > token-standard-scoped assignment > global assignment >
-- compiled-in default (FinalityPolicyDefaults — code, not a seed row, so a fresh database, an
-- integration test, and a failed migration all behave identically).
--
-- No gate call sites exist yet (that is a later phase) — this migration only makes the policy
-- itself configurable and auditable ahead of time.

CREATE TABLE finality_policy_assignment (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type      VARCHAR(20)   NOT NULL,
    token_standard  VARCHAR(30),
    asset_id        UUID          REFERENCES asset(id),
    profile         VARCHAR(20)   NOT NULL,
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_finality_policy_assignment_scope_type CHECK (scope_type IN ('GLOBAL', 'TOKEN_STANDARD', 'ASSET')),
    CONSTRAINT chk_finality_policy_assignment_profile CHECK (profile IN ('FAST', 'BALANCED', 'CONSERVATIVE')),
    -- Exactly one of token_standard/asset_id is set, matching scope_type — enforced here (not
    -- just in application code) since this table is small and directly operator-editable.
    CONSTRAINT chk_finality_policy_assignment_scope_shape CHECK (
        (scope_type = 'GLOBAL' AND token_standard IS NULL AND asset_id IS NULL) OR
        (scope_type = 'TOKEN_STANDARD' AND token_standard IS NOT NULL AND asset_id IS NULL) OR
        (scope_type = 'ASSET' AND asset_id IS NOT NULL AND token_standard IS NULL)
    )
);

-- At most one assignment per scope — partial unique indexes because a plain UNIQUE(scope_type,
-- token_standard, asset_id) would not work: Postgres treats each NULL as distinct, so it would not
-- actually stop two GLOBAL rows (both NULL/NULL) from coexisting.
CREATE UNIQUE INDEX uq_finality_policy_assignment_global ON finality_policy_assignment (scope_type) WHERE scope_type = 'GLOBAL';
CREATE UNIQUE INDEX uq_finality_policy_assignment_token_standard ON finality_policy_assignment (token_standard) WHERE scope_type = 'TOKEN_STANDARD';
CREATE UNIQUE INDEX uq_finality_policy_assignment_asset ON finality_policy_assignment (asset_id) WHERE scope_type = 'ASSET';

-- The audited, step-up-protected escape hatch — stays empty normally. One row per (asset,
-- operation): the most specific rung in the resolution chain, always wins over any assignment.
-- No expiry column: an override is removed by an explicit, audited delete, not left to lapse
-- silently.
CREATE TABLE finality_policy_override (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID          NOT NULL REFERENCES asset(id),
    operation       VARCHAR(50)   NOT NULL,
    required_level  VARCHAR(16)   NOT NULL,
    reason          TEXT          NOT NULL,
    created_by      UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_finality_policy_override_level CHECK (required_level IN ('PROVISIONAL', 'SAFE', 'FINALIZED')),
    CONSTRAINT uq_finality_policy_override UNIQUE (asset_id, operation)
);

CREATE INDEX idx_finality_policy_override_asset ON finality_policy_override (asset_id);
