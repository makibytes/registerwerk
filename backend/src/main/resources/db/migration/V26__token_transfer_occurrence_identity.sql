-- A transaction/log position is not globally unique across EVM block incarnations: a transaction
-- can be re-mined after a reorg, and an earlier block can later become canonical again (A->B->A).
-- Keep exact block identity in the occurrence key so the B row does not collide with historical A.
--
-- token_transfer is RANGE-partitioned by occurred_at. PostgreSQL therefore requires occurred_at
-- in every UNIQUE constraint declared on the partitioned parent; the Graph Node value is the
-- immutable block timestamp and the application includes it in its exact-occurrence lookup.
ALTER TABLE token_transfer DROP CONSTRAINT uq_transfer_evm;

-- Transaction identity follows the same protocol-safe rule as block identity: prefixed hex is
-- case-insensitive, while Solana/Stellar/other non-hex identifiers remain byte-for-byte exact.
UPDATE token_transfer
SET tx_hash = lower(tx_hash)
WHERE tx_hash ~ '^0[xX][0-9A-Fa-f]+$';

ALTER TABLE token_transfer
    ADD CONSTRAINT uq_transfer_evm UNIQUE NULLS NOT DISTINCT
        (chain_config_id, tx_hash, log_index, block_hash, occurred_at);

ALTER TABLE token_transfer
    ADD CONSTRAINT chk_token_transfer_normalized_hex_tx_hash CHECK (
        tx_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR tx_hash = lower(tx_hash)
    );
