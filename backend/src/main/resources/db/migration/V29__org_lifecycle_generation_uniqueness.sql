-- A logical org identity may have more than one historical row: an old binding/grant/issuer is
-- removed or revoked, then a replacement is added.  During a suffix reorg, LIFO compensation first
-- returns the replacement addition to PENDING and then returns the predecessor removal/revocation
-- to its fail-closed pending state.  A partial unique index spanning both lifecycle generations
-- makes that correct unwind impossible.
--
-- Keep one add-side PENDING/ACTIVE generation, but let retiring predecessors coexist.  Service
-- lifecycle checks continue to reject a new request while a predecessor is unresolved.  The DROP
-- statements also repair databases that already applied the wider V23/V24 index definitions.

DROP INDEX IF EXISTS uq_org_member_wallet_live;
CREATE UNIQUE INDEX uq_org_member_wallet_live
    ON org_member_wallet (chain_config_id, lower(wallet_address))
    WHERE status IN ('PENDING','ACTIVE');

DROP INDEX IF EXISTS uq_ecosystem_trusted_issuer_live;
CREATE UNIQUE INDEX uq_ecosystem_trusted_issuer_live
    ON ecosystem_trusted_issuer (chain_config_id, lower(issuer_address))
    WHERE status IN ('PENDING','ACTIVE');

DROP INDEX IF EXISTS uq_permission_grant_live_org;
CREATE UNIQUE INDEX uq_permission_grant_live_org
    ON permission_grant (permission_definition_id, org_registration_id)
    WHERE grant_type = 'ORG'
      AND status IN ('PENDING','ACTIVE');

DROP INDEX IF EXISTS uq_permission_grant_live_role;
CREATE UNIQUE INDEX uq_permission_grant_live_role
    ON permission_grant (permission_definition_id, org_registration_id, role_code)
    WHERE grant_type = 'ROLE'
      AND status IN ('PENDING','ACTIVE');

-- Repair already-upgraded databases on which V21 could not correlate a legacy terminal row to an
-- exact chain-effect incarnation. New V21 upgrades perform these same fail-closed transitions in
-- place; repeating them here is idempotent and prevents old installations from remaining silently
-- uncompensable.
UPDATE org_registration
SET status = 'PENDING', confirmed_block_number = NULL, confirmed_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (confirmed_block_number IS NULL OR confirmed_block_hash IS NULL);

UPDATE org_member_wallet
SET status = 'PENDING', bound_block_number = NULL, bound_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (bound_block_number IS NULL OR bound_block_hash IS NULL);

UPDATE permission_grant
SET status = 'PENDING', granted_chain_config_id = NULL,
    granted_block_number = NULL, granted_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (granted_chain_config_id IS NULL
       OR granted_block_number IS NULL
       OR granted_block_hash IS NULL);

UPDATE ecosystem_trusted_issuer
SET status = 'PENDING', added_block_number = NULL, added_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (added_block_number IS NULL OR added_block_hash IS NULL);

UPDATE onchain_identity
SET identity_address = '0x-PENDING-ONCHAINID-' || id::text,
    deployed_block_number = NULL,
    deployed_block_hash = NULL
WHERE identity_address NOT LIKE '0x-PENDING-%'
  AND identity_address NOT LIKE '0x-FAILED-%'
  AND (deployed_block_number IS NULL OR deployed_block_hash IS NULL);

UPDATE onchain_claim
SET confirmed = FALSE, chain_config_id = NULL, block_number = NULL, block_hash = NULL
WHERE confirmed = TRUE
  AND (chain_config_id IS NULL OR block_number IS NULL OR block_hash IS NULL);

UPDATE erc3643_identity_registry
SET registration_confirmed = FALSE,
    registration_block_number = NULL,
    registration_block_hash = NULL,
    removed_at = COALESCE(removed_at, now())
WHERE registration_confirmed = TRUE
  AND (chain_config_id IS NULL
       OR registration_block_number IS NULL
       OR registration_block_hash IS NULL);

UPDATE erc3643_identity_registry
SET removal_confirmed = FALSE,
    removal_block_number = NULL,
    removal_block_hash = NULL
WHERE removal_confirmed = TRUE
  AND (chain_config_id IS NULL
       OR removal_block_number IS NULL
       OR removal_block_hash IS NULL);
