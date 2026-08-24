-- Per-chain finality model: how a transaction/block on a chain becomes irreversible.
-- DEPTH_BASED preserves the confirmation-depth-only behavior this registry always had, so
-- existing rows are unaffected. TAG_BASED lets an EVM chain with real safe/finalized block tags
-- (Ethereum L1, OP-Stack L2s) be confirmed against its actual finality guarantee instead of a
-- depth heuristic; INSTANT is for permissioned BFT chains (Besu/Quorum QBFT, IBFT) that cannot
-- reorg, where the first-seen receipt is already final.
ALTER TABLE chain_config
    ADD COLUMN finality_model VARCHAR(20) NOT NULL DEFAULT 'DEPTH_BASED';

ALTER TABLE chain_config
    ADD CONSTRAINT chk_finality_model CHECK (finality_model IN ('TAG_BASED', 'DEPTH_BASED', 'INSTANT'));
