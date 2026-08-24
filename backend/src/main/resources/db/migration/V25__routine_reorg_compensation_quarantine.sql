-- A ROUTINE canonical reorg is normally applied automatically. If any local business-effect
-- compensation fails or is irreversible, however, that same durable episode becomes the incident
-- backing a chain-wide quarantine. Keeping the original episode severity preserves the distinction
-- between a consensus finality violation and a local compensation failure.
ALTER TABLE chain_quarantine
    DROP CONSTRAINT chk_chain_quarantine_severity;

ALTER TABLE chain_quarantine
    ADD CONSTRAINT chk_chain_quarantine_severity
        CHECK (severity IN ('ROUTINE', 'FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY'));

ALTER TABLE chain_quarantine
    ADD COLUMN trigger_reason VARCHAR(48),
    ADD COLUMN trigger_detail TEXT;

UPDATE chain_quarantine
SET trigger_reason = CASE severity
    WHEN 'FINALITY_VIOLATION' THEN 'CONSENSUS_FINALITY_VIOLATION'
    ELSE 'UNRESOLVED_ANCESTRY'
END;

ALTER TABLE chain_quarantine
    ALTER COLUMN trigger_reason SET NOT NULL,
    ADD CONSTRAINT chk_chain_quarantine_trigger CHECK (trigger_reason IN (
        'CONSENSUS_FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY', 'LOCAL_FINALITY_CONFLICT',
        'INDEXER_COMPENSATION_FAILED', 'DOMAIN_COMPENSATION_FAILED', 'REORG_ID_COLLISION'
    ));

-- Block identity has the same protocol-safe invariant as block_finality and chain_effect:
-- EVM-style 0x-prefixed hexadecimal identities are case-insensitive and canonicalized lowercase;
-- identifiers from case-sensitive protocols are left byte-for-byte unchanged.
UPDATE token_transfer
SET block_hash = lower(block_hash)
WHERE block_hash ~ '^0[xX][0-9A-Fa-f]+$';

ALTER TABLE token_transfer
    ADD CONSTRAINT chk_token_transfer_normalized_hex_block_hash CHECK (
        block_hash IS NULL
        OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR block_hash = lower(block_hash)
    );
