-- A height can have several block incarnations across reorgs. Keep every incarnation for the
-- regulated audit trail, while making "which block is canonical at this height right now" an
-- explicit, database-enforced property.

ALTER TABLE block_finality DROP CONSTRAINT uq_block_finality;

ALTER TABLE block_finality
    ADD COLUMN canonical BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN orphaned_at TIMESTAMPTZ;

-- Preserve the meaning of every existing row. Existing ORPHANED rows are historical
-- incarnations; all other rows were the sole (therefore canonical) observation at their height.
UPDATE block_finality
SET canonical = FALSE,
    orphaned_at = updated_at
WHERE finality_level = 'ORPHANED';

-- EVM/Starknet hex identities are case-insensitive. Canonicalize legacy spellings so a replay
-- that changes only hex case cannot manufacture a second incarnation. Case-sensitive identities
-- such as Solana base58 hashes are deliberately untouched.
UPDATE block_finality
SET block_hash = lower(block_hash)
WHERE block_hash ~ '^0[xX][0-9A-Fa-f]+$';

-- The producer contract still permits a null/non-hash chain identity for legacy and non-EVM
-- probes. NULLS NOT DISTINCT prevents duplicate unidentified incarnations at one height until
-- the chain-specific phase upgrades every producer to a true protocol block hash.
ALTER TABLE block_finality
    ADD CONSTRAINT uq_block_finality_incarnation
        UNIQUE NULLS NOT DISTINCT (chain_config_id, block_number, block_hash),
    ADD CONSTRAINT ck_block_finality_canonical_level CHECK (
        (canonical AND finality_level <> 'ORPHANED')
        OR (NOT canonical AND finality_level = 'ORPHANED')
    ),
    ADD CONSTRAINT ck_block_finality_normalized_hex_hash CHECK (
        block_hash IS NULL
        OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR block_hash = lower(block_hash)
    );

-- PostgreSQL partial uniqueness is the authoritative concurrency guard: history is unlimited,
-- but at most one incarnation can be current for a (chain, height).
CREATE UNIQUE INDEX uq_block_finality_canonical_height
    ON block_finality (chain_config_id, block_number)
    WHERE canonical;

CREATE INDEX idx_block_finality_incarnation_history
    ON block_finality (chain_config_id, block_number, observed_at, id);

-- Block identity is append-only. Finality/canonical state may advance, but an incarnation can
-- never be rewritten to masquerade as a different block or height.
CREATE FUNCTION rw_reject_block_finality_identity_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.chain_config_id IS DISTINCT FROM OLD.chain_config_id
       OR NEW.block_number IS DISTINCT FROM OLD.block_number
       OR NEW.block_hash IS DISTINCT FROM OLD.block_hash THEN
        RAISE EXCEPTION 'block_finality incarnation identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_block_finality_immutable_identity
    BEFORE UPDATE OF chain_config_id, block_number, block_hash ON block_finality
    FOR EACH ROW
    EXECUTE FUNCTION rw_reject_block_finality_identity_change();
