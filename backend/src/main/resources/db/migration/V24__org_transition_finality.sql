-- Org suspension/reinstatement, member removal and role restriction are distinct chain
-- transactions.  Their previous mirrors wrote terminal values at request time and discarded the
-- transaction hash, making receipt failure and reorg compensation impossible.  Persist intent,
-- exact finalized receipt provenance and a fail-closed retry state for each transition.

ALTER TABLE org_registration DROP CONSTRAINT chk_org_registration_status;
ALTER TABLE org_registration ALTER COLUMN status TYPE VARCHAR(24);
ALTER TABLE org_registration
    ADD COLUMN status_tx VARCHAR(66),
    ADD COLUMN status_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN status_block_number BIGINT,
    ADD COLUMN status_block_hash VARCHAR(128),
    ADD COLUMN status_requested_at TIMESTAMPTZ;

-- Legacy SUSPENDED rows were optimistic requests, not verified chain facts.
UPDATE org_registration
SET status = 'SUSPEND_PENDING',
    status_requested_at = COALESCE(suspended_at, created_at)
WHERE status = 'SUSPENDED';

ALTER TABLE org_registration
    ADD CONSTRAINT chk_org_registration_status CHECK (
        status IN ('PENDING','ACTIVE','SUSPEND_PENDING','SUSPENDED','SUSPEND_FAILED',
                   'REINSTATE_PENDING','REINSTATE_FAILED','FAILED')
    ),
    ADD CONSTRAINT chk_org_status_causality_complete CHECK (
        (status_chain_config_id IS NULL AND status_block_number IS NULL AND status_block_hash IS NULL)
        OR
        (status_tx IS NOT NULL AND status_chain_config_id IS NOT NULL
            AND status_block_number IS NOT NULL AND status_block_hash IS NOT NULL)
    ),
    ADD CONSTRAINT chk_org_status_terminal_provenance CHECK (
        status NOT IN ('SUSPENDED','SUSPEND_FAILED','REINSTATE_FAILED')
        OR (status_tx IS NOT NULL AND status_chain_config_id IS NOT NULL
            AND status_block_number IS NOT NULL AND status_block_hash IS NOT NULL)
    );

ALTER TABLE org_member_wallet DROP CONSTRAINT chk_org_member_wallet_status;
ALTER TABLE org_member_wallet
    ADD COLUMN removed_tx VARCHAR(66),
    ADD COLUMN removed_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN removed_block_number BIGINT,
    ADD COLUMN removed_block_hash VARCHAR(128);

-- Legacy REMOVED rows likewise carried no receipt and must be re-verified.
UPDATE org_member_wallet
SET status = 'REMOVAL_PENDING',
    removed_at = COALESCE(removed_at, created_at)
WHERE status = 'REMOVED';

ALTER TABLE org_member_wallet
    ADD CONSTRAINT chk_org_member_wallet_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVAL_PENDING','REMOVED','REMOVAL_FAILED','FAILED')
    ),
    ADD CONSTRAINT chk_member_removal_causality_complete CHECK (
        (removed_chain_config_id IS NULL AND removed_block_number IS NULL AND removed_block_hash IS NULL)
        OR
        (removed_tx IS NOT NULL AND removed_chain_config_id IS NOT NULL
            AND removed_block_number IS NOT NULL AND removed_block_hash IS NOT NULL)
    ),
    ADD CONSTRAINT chk_member_removal_terminal_provenance CHECK (
        status NOT IN ('REMOVED','REMOVAL_FAILED')
        OR (removed_tx IS NOT NULL AND removed_chain_config_id IS NOT NULL
            AND removed_block_number IS NOT NULL AND removed_block_hash IS NOT NULL)
    );

-- Binding generations remain unique, while a retiring predecessor is excluded so LIFO reorg
-- compensation can represent both the replacement binding and the predecessor removal as pending.
-- MemberWalletService still rejects a fresh bind while a predecessor removal is unresolved.
DROP INDEX uq_org_member_wallet_live;
CREATE UNIQUE INDEX uq_org_member_wallet_live
    ON org_member_wallet (chain_config_id, lower(wallet_address))
    WHERE status IN ('PENDING','ACTIVE');

ALTER TABLE permission_grant
    ADD COLUMN role_restriction_status VARCHAR(20) NOT NULL DEFAULT 'STABLE',
    ADD COLUMN requested_role_restricted BOOLEAN,
    ADD COLUMN role_restriction_tx VARCHAR(66),
    ADD COLUMN role_restriction_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN role_restriction_block_number BIGINT,
    ADD COLUMN role_restriction_block_hash VARCHAR(128),
    ADD COLUMN role_restriction_requested_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_role_restriction_status CHECK (
        role_restriction_status IN ('STABLE','CHANGE_PENDING','CHANGE_FAILED')
    ),
    ADD CONSTRAINT chk_role_restriction_request CHECK (
        (role_restriction_status = 'STABLE' AND requested_role_restricted IS NULL)
        OR (role_restriction_status IN ('CHANGE_PENDING','CHANGE_FAILED')
            AND requested_role_restricted IS NOT NULL AND role_restriction_requested_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_role_restriction_causality_complete CHECK (
        (role_restriction_chain_config_id IS NULL AND role_restriction_block_number IS NULL
            AND role_restriction_block_hash IS NULL)
        OR
        (role_restriction_tx IS NOT NULL AND role_restriction_chain_config_id IS NOT NULL
            AND role_restriction_block_number IS NOT NULL AND role_restriction_block_hash IS NOT NULL)
    ),
    ADD CONSTRAINT chk_role_restriction_failed_provenance CHECK (
        role_restriction_status <> 'CHANGE_FAILED'
        OR (role_restriction_tx IS NOT NULL AND role_restriction_chain_config_id IS NOT NULL
            AND role_restriction_block_number IS NOT NULL AND role_restriction_block_hash IS NOT NULL)
    );

CREATE INDEX idx_permission_grant_role_restriction_pending
    ON permission_grant (role_restriction_status)
    WHERE role_restriction_status = 'CHANGE_PENDING';
