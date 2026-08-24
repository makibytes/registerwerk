-- Revocation/removal is asynchronous.  The old model wrote the terminal status when the user
-- merely requested a transaction and never inspected that transaction's receipt.  Distinct
-- fail-closed intent/failure states prevent an unconfirmed or reverted operation from being
-- represented as a confirmed chain fact.

ALTER TABLE permission_grant DROP CONSTRAINT chk_permission_grant_status;
ALTER TABLE ecosystem_trusted_issuer DROP CONSTRAINT chk_ecosystem_trusted_issuer_status;

-- No legacy terminal row carries confirming-block provenance, because those receipts were never
-- polled.  Reclassify it as an outstanding intent so the poller verifies the recorded tx hash.
UPDATE permission_grant
SET status = 'REVOCATION_PENDING'
WHERE status = 'REVOKED';

UPDATE ecosystem_trusted_issuer
SET status = 'REMOVAL_PENDING'
WHERE status = 'REMOVED';

ALTER TABLE permission_grant
    ADD COLUMN revoked_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN revoked_block_number BIGINT,
    ADD COLUMN revoked_block_hash VARCHAR(128),
    ADD CONSTRAINT chk_permission_grant_status CHECK (
        status IN ('PENDING','ACTIVE','REVOCATION_PENDING','REVOKED','REVOCATION_FAILED','FAILED')
    ),
    ADD CONSTRAINT chk_permission_grant_revocation_provenance CHECK (
        (status = 'REVOKED') =
        (revoked_chain_config_id IS NOT NULL
            AND revoked_block_number IS NOT NULL
            AND revoked_block_hash IS NOT NULL
            AND revoked_tx IS NOT NULL)
    );

ALTER TABLE ecosystem_trusted_issuer
    ADD COLUMN removed_block_number BIGINT,
    ADD COLUMN removed_block_hash VARCHAR(128),
    ADD CONSTRAINT chk_ecosystem_trusted_issuer_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVAL_PENDING','REMOVED','REMOVAL_FAILED','FAILED')
    ),
    ADD CONSTRAINT chk_trusted_issuer_removal_provenance CHECK (
        (status = 'REMOVED') =
        (removed_block_number IS NOT NULL
            AND removed_block_hash IS NOT NULL
            AND removed_tx IS NOT NULL)
    );

CREATE INDEX idx_ecosystem_trusted_issuer_status
    ON ecosystem_trusted_issuer (status);

-- Addition generations remain unique.  Retiring predecessors are deliberately excluded: after
-- a confirmed removal and replacement, a suffix reorg must be able to return both the replacement
-- addition and the predecessor removal to pending during LIFO compensation.  Application-level
-- lifecycle checks still reject a fresh addition while a predecessor removal is unresolved.
DROP INDEX uq_ecosystem_trusted_issuer_live;
CREATE UNIQUE INDEX uq_ecosystem_trusted_issuer_live
    ON ecosystem_trusted_issuer (chain_config_id, lower(issuer_address))
    WHERE status IN ('PENDING','ACTIVE');


-- Service checks give a useful error; these indexes also close the concurrent-request race.
CREATE UNIQUE INDEX uq_permission_grant_live_org
    ON permission_grant (permission_definition_id, org_registration_id)
    WHERE grant_type = 'ORG'
      AND status IN ('PENDING','ACTIVE');

CREATE UNIQUE INDEX uq_permission_grant_live_role
    ON permission_grant (permission_definition_id, org_registration_id, role_code)
    WHERE grant_type = 'ROLE'
      AND status IN ('PENDING','ACTIVE');
