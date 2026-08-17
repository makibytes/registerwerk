-- ═══════════════════════════════════════════════════════════════════════════
-- V4 — Production-readiness Phase 2: chain finality and reorg safety.
--
-- Builds on V3's partitioned token_transfer/blockchain_transaction (PARTITION BY RANGE on
-- occurred_at / created_at, composite PKs (id, <partition_col>)). Every new index below is
-- created against the partitioned parent, so Postgres automatically attaches a matching
-- "partitioned index" to every existing and future monthly partition (see V3 header) — no
-- per-partition DDL is needed here.
--
-- Four things happen, in order:
--   1. token_transfer gains a two-tier finality model: finality_status
--      (PROVISIONAL / FINAL / ORPHANED) + block_hash, so a shallow reorg can be detected and
--      the affected rows marked (never deleted — this is a regulated register with an audit
--      trail requirement, same principle as chain_drift_event: wrong state gets a status
--      column, not a DELETE).
--   2. blockchain_transaction (the tx-submission side) gains block_hash so a previously-mined
--      receipt can be re-verified against the canonical chain on every subsequent poll before
--      its confirmation count is trusted.
--   3. indexer_state gains last_final_block, separating "confirmed/final cursor" from the
--      existing last_synced_block ("head/provisional cursor" — unchanged meaning).
--   4. asset_deployment gains block_hash + block_number for the same reason as (2): deployment
--      confirmation previously flipped to CONFIRMED on the first receipt seen (zero confirmation
--      depth) — AssetDeploymentService.syncFromChain now gates on the same
--      confirmations-by-chain policy and needs somewhere to persist the baseline block it is
--      re-verifying on each subsequent poll.
-- ═══════════════════════════════════════════════════════════════════════════


-- ═══════════════════════════════════════════════════════════════════════════
-- 1. token_transfer — finality tracking
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE token_transfer
    ADD COLUMN finality_status VARCHAR(12) NOT NULL DEFAULT 'FINAL'
        CHECK (finality_status IN ('PROVISIONAL', 'FINAL', 'ORPHANED')),
    ADD COLUMN block_hash VARCHAR(128);

COMMENT ON COLUMN token_transfer.finality_status IS
    'PROVISIONAL: within the configured confirmation depth (registerwerk.blockchain.tx.'
    'confirmations-by-chain), not yet re-verified against a re-fetched canonical block/status. '
    'FINAL: cleared the confirmation depth (EVM) or is chain-natively final on write (Solana at '
    'commitment=finalized, Stellar ledger close, Canton synchronizer commit, Starknet '
    'ACCEPTED_ON_L1). ORPHANED: a later re-verification found this row''s recorded block/tx was '
    'no longer canonical (EVM hash mismatch, Starknet REJECTED) — the row is kept, never '
    'deleted, so the audit trail shows exactly what the indexer believed and when it was '
    'corrected; downstream balance readers must exclude ORPHANED rows. '
    'Existing rows (indexed before this mechanism existed, under the old always-final '
    'assumption) are backfilled to FINAL by the column default above. This is a documented '
    'limitation, not a retroactive finality proof: those rows were never re-verified against a '
    'fork-point block hash the way rows written after this migration are. Re-verifying the '
    'entire historical backlog against each chain''s current canonical chain is out of scope for '
    'this migration (F0-007 remains BLOCKED_DECISION on what a retroactive re-verification '
    'policy — and the trusted-RPC/quorum policy it would depend on — should even be); a FINAL '
    'backfill was chosen over inventing an UNKNOWN state because no code path currently branches '
    'on finality_status for historical data and a fourth enum value would need to thread through '
    'every downstream consumer for no behavioral benefit today.';

COMMENT ON COLUMN token_transfer.block_hash IS
    'Canonical block/identity hash recorded at write time, used to detect a reorg by comparing '
    'against a freshly re-fetched hash for the same height. Nullable: only meaningful for chains '
    'with an EVM-style probabilistic-finality/hash-reorg model. Left null for Solana (commitment '
    '=finalized already final on write), Stellar (Horizon returns only closed-ledger data — no '
    'reorg primitive exists), Canton (synchronizer commits are final, no reorg primitive), and '
    'for EVM/Starknet rows written already past the configured confirmation depth (no need to '
    'ever re-verify a block we will not re-check).';

-- Used by ReorgGuard to find the provisional window per chain without a full-table scan; a
-- partial index (excludes the FINAL majority) kept small since only PROVISIONAL/ORPHANED rows
-- are ever queried by finality_status. Automatically becomes a partitioned index (see header).
CREATE INDEX idx_transfer_finality
    ON token_transfer (chain_config_id, finality_status, block_number)
    WHERE finality_status <> 'FINAL';


-- ═══════════════════════════════════════════════════════════════════════════
-- 2. blockchain_transaction — submission-side reorg re-verification
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE blockchain_transaction
    ADD COLUMN block_hash VARCHAR(66);

COMMENT ON COLUMN blockchain_transaction.block_hash IS
    'Receipt block hash recorded the first poll a receipt is seen. Re-verified on every '
    'subsequent poll before advancing the confirmation count (see BlockchainTransactionService.'
    'pollPendingTransactions) — a mismatch means the tx''s block is no longer canonical, and the '
    'transaction is reset to PENDING (not FAILED: it may simply need to be re-mined). Actual '
    'resubmission with nonce/gas handling remains deliberately unimplemented (see '
    'NonceCoordinator''s Javadoc); this migration only lays the block-hash persistence '
    'groundwork it will need.';


-- ═══════════════════════════════════════════════════════════════════════════
-- 3. indexer_state — separate the final cursor from the head/provisional cursor
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE indexer_state
    ADD COLUMN last_final_block BIGINT;

COMMENT ON COLUMN indexer_state.last_final_block IS
    'Highest block number whose token_transfer rows have all been re-verified and flipped to '
    'FINAL. last_synced_block (pre-existing) keeps its original meaning: the head/provisional '
    'cursor — the highest block the indexer has read transfers from at all, FINAL or still '
    'PROVISIONAL. last_final_block <= last_synced_block always; on a detected reorg both are '
    'rewound to (fork_block - 1) if the fork reaches at or below the previous last_final_block '
    '(i.e. a reorg deeper than the configured confirmation policy — logged as CRITICAL, since it '
    'violates the assumption the confirmation-depth policy exists to guarantee). Null before the '
    'first successful finality pass, and for indexer types with no two-tier model (Solana, '
    'Stellar, Canton — see token_transfer.finality_status comment) where it simply tracks '
    'the same value as last_synced_block once set.';


-- ═══════════════════════════════════════════════════════════════════════════
-- 4. asset_deployment — deployment-confirmation reorg re-verification
-- ═══════════════════════════════════════════════════════════════════════════

-- Same shape as blockchain_transaction.block_hash above: previously,
-- AssetDeploymentService.syncFromChain (EVM) confirmed a deployment the instant a receipt was
-- seen at all -- zero confirmation depth, and no baseline to detect the deployment tx being
-- reorged out before it was CONFIRMED. block_number is stored alongside so the confirmation-depth
-- check does not need a second receipt fetch to recover it.
ALTER TABLE asset_deployment
    ADD COLUMN block_hash   VARCHAR(66),
    ADD COLUMN block_number BIGINT;

COMMENT ON COLUMN asset_deployment.block_hash IS
    'EVM deployment-tx receipt block hash, recorded the first poll a receipt is seen and '
    're-verified on every subsequent poll before advancing the confirmation count (see '
    'AssetDeploymentService.syncFromChain). A mismatch means the block is no longer canonical; '
    'the recorded block is cleared and the deployment stays PENDING for re-verification, mirroring '
    'BlockchainTransactionCompletionWriter.resetToPendingAfterReorg. Null for Starknet (finality '
    'is tracked via the tx''s own finality_status, not a block hash -- see '
    'AssetDeploymentService''s Starknet confirmation path) and for every other non-EVM chain, '
    'which either has no separate confirmation-depth concept (Solana/Stellar/Canton) or is not '
    'yet auto-confirmed at all (a pre-existing gap, not introduced or closed by this migration).';
