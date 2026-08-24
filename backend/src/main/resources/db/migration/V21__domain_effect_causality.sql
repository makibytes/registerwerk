-- Domain rows must retain the exact block incarnation that last moved them into an
-- on-chain-confirmed state.  A status alone (ACTIVE/CONFIRMED) is not a causal token: after a
-- reorg, the same transaction may be re-mined in a different block and confirm the same row
-- again.  Compensating the older incarnation must not undo that newer canonical confirmation.

ALTER TABLE org_registration
    ADD COLUMN confirmed_block_number BIGINT,
    ADD COLUMN confirmed_block_hash VARCHAR(128);

ALTER TABLE org_member_wallet
    ADD COLUMN bound_block_number BIGINT,
    ADD COLUMN bound_block_hash VARCHAR(128);

ALTER TABLE permission_grant
    ADD COLUMN granted_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN granted_block_number BIGINT,
    ADD COLUMN granted_block_hash VARCHAR(128);

ALTER TABLE ecosystem_trusted_issuer
    ADD COLUMN added_block_number BIGINT,
    ADD COLUMN added_block_hash VARCHAR(128);

ALTER TABLE onchain_identity
    ADD COLUMN deployed_block_number BIGINT,
    ADD COLUMN deployed_block_hash VARCHAR(128);

ALTER TABLE onchain_claim
    ADD COLUMN block_hash VARCHAR(128),
    ADD COLUMN revocation_chain_config_id UUID REFERENCES chain_config(id),
    ADD COLUMN revocation_block_number BIGINT,
    ADD COLUMN revocation_block_hash VARCHAR(128);

ALTER TABLE erc3643_identity_registry
    ADD COLUMN registration_block_number BIGINT,
    ADD COLUMN registration_block_hash VARCHAR(128),
    ADD COLUMN removal_block_number BIGINT,
    ADD COLUMN removal_block_hash VARCHAR(128);

-- Preserve causality for already-journalled pre-migration confirmations.  The most recently
-- recorded forward effect is the only incarnation that can currently own the row's forward
-- state; older effects remain audit history but cannot pass the domain guard.
UPDATE org_registration r
SET (confirmed_block_number, confirmed_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = r.id AND ce.effect_type = 'ORG_REGISTRATION_CONFIRMED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE r.status = 'ACTIVE';

UPDATE org_member_wallet w
SET (bound_block_number, bound_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = w.id AND ce.effect_type = 'MEMBER_WALLET_BOUND'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE w.status = 'ACTIVE';

UPDATE permission_grant g
SET (granted_chain_config_id, granted_block_number, granted_block_hash) = (
    SELECT ce.chain_config_id, ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = g.id AND ce.effect_type = 'PERMISSION_GRANTED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE g.status = 'ACTIVE';

UPDATE ecosystem_trusted_issuer i
SET (added_block_number, added_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = i.id AND ce.effect_type = 'TRUSTED_ISSUER_ADDED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE i.status = 'ACTIVE';

UPDATE onchain_identity i
SET (deployed_block_number, deployed_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = i.id AND ce.effect_type = 'ONCHAIN_IDENTITY_DEPLOYED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE i.identity_address NOT LIKE '0x-PENDING-%' AND i.identity_address NOT LIKE '0x-FAILED-%';

UPDATE onchain_claim c
SET block_hash = (
    SELECT ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = c.id AND ce.effect_type = 'ERC3643_CLAIM_CONFIRMED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE c.confirmed = TRUE;

UPDATE erc3643_identity_registry r
SET (registration_block_number, registration_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = r.id AND ce.effect_type = 'ERC3643_IDENTITY_REGISTERED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE r.registration_confirmed = TRUE;

UPDATE erc3643_identity_registry r
SET (removal_block_number, removal_block_hash) = (
    SELECT ce.block_number, ce.block_hash FROM chain_effect ce
    WHERE ce.entity_id = r.id AND ce.effect_type = 'ERC3643_IDENTITY_REMOVED'
    ORDER BY ce.journal_sequence DESC LIMIT 1
)
WHERE r.removal_confirmed = TRUE;

-- A legacy terminal row without a matching exact journal incarnation is not safely compensable.
-- Deliberately return it to its existing receipt-verification path (or soft-remove it for the
-- optimistic ERC-3643 registration mirror) instead of silently retaining fail-open state.
UPDATE org_registration
SET status = 'PENDING',
    confirmed_block_number = NULL,
    confirmed_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (confirmed_block_number IS NULL OR confirmed_block_hash IS NULL);

UPDATE org_member_wallet
SET status = 'PENDING',
    bound_block_number = NULL,
    bound_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (bound_block_number IS NULL OR bound_block_hash IS NULL);

UPDATE permission_grant
SET status = 'PENDING',
    granted_chain_config_id = NULL,
    granted_block_number = NULL,
    granted_block_hash = NULL
WHERE status = 'ACTIVE'
  AND (granted_chain_config_id IS NULL
       OR granted_block_number IS NULL
       OR granted_block_hash IS NULL);

UPDATE ecosystem_trusted_issuer
SET status = 'PENDING',
    added_block_number = NULL,
    added_block_hash = NULL
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
SET confirmed = FALSE,
    chain_config_id = NULL,
    block_number = NULL,
    block_hash = NULL
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
