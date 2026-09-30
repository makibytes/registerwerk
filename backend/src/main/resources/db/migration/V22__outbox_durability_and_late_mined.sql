-- Phase 4 K4b (P4B-4 / P4B-5, parked T4-03 interim): durable-outbox recovery and TIMEOUT semantics.
--
-- evm_signed_submission gains the states a stuck nonce needs (SUPERSEDED = a replacement at the same
-- nonce exists, the original may still be mined; ABANDONED = the nonce is proven consumed by a
-- different transaction), failure classification, back-off scheduling and operator evidence.
-- The (chain_id, sender_address, nonce) uniqueness now only binds ACTIVE rows, otherwise a re-priced
-- or cancelling replacement could never share the nonce of the transaction it replaces.
ALTER TABLE evm_signed_submission
    ADD COLUMN first_failed_at       TIMESTAMPTZ,
    ADD COLUMN last_error_class      VARCHAR(30),
    ADD COLUMN next_attempt_at       TIMESTAMPTZ,
    ADD COLUMN kind                  VARCHAR(20)  NOT NULL DEFAULT 'OPERATION',
    ADD COLUMN replaces_tx_hash      VARCHAR(66),
    ADD COLUMN superseded_by_tx_hash VARCHAR(66),
    ADD COLUMN abandoned_at          TIMESTAMPTZ,
    ADD COLUMN abandoned_by          VARCHAR(255),
    ADD COLUMN abandon_approver_id   UUID,
    ADD COLUMN abandon_reason        TEXT,
    ADD COLUMN last_rebroadcast_at   TIMESTAMPTZ,
    ADD COLUMN rebroadcast_count     INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN stuck_alerted_at      TIMESTAMPTZ,
    ADD CONSTRAINT chk_evm_signed_submission_kind CHECK (kind IN ('OPERATION', 'REPRICE', 'CANCEL'));

-- migration-safety: ack (relaxes a CHECK constraint on a small table; no row is removed or rewritten)
ALTER TABLE evm_signed_submission DROP CONSTRAINT chk_evm_signed_submission_status;
ALTER TABLE evm_signed_submission ADD CONSTRAINT chk_evm_signed_submission_status
    CHECK (status IN ('PREPARED', 'BROADCAST', 'SUPERSEDED', 'ABANDONED'));

-- migration-safety: ack (relaxes a CHECK constraint on a small table; no row is removed or rewritten)
ALTER TABLE evm_signed_submission DROP CONSTRAINT chk_evm_signed_submission_broadcast;
ALTER TABLE evm_signed_submission ADD CONSTRAINT chk_evm_signed_submission_broadcast CHECK (
    (status = 'PREPARED' AND broadcast_at IS NULL)
    OR (status = 'BROADCAST' AND broadcast_at IS NOT NULL)
    OR status IN ('SUPERSEDED', 'ABANDONED')
);

-- migration-safety: ack (constraint replaced by a strictly weaker unique index over active rows; existing rows all satisfy it)
ALTER TABLE evm_signed_submission DROP CONSTRAINT uq_evm_signed_submission_nonce;
CREATE UNIQUE INDEX uq_evm_signed_submission_active_nonce
    ON evm_signed_submission (chain_id, sender_address, nonce)
    WHERE status IN ('PREPARED', 'BROADCAST');
CREATE INDEX idx_evm_signed_submission_nonce ON evm_signed_submission (chain_id, sender_address, nonce);

DROP INDEX idx_evm_signed_submission_pending;
CREATE INDEX idx_evm_signed_submission_pending
    ON evm_signed_submission (chain_id, sender_address, nonce) WHERE status = 'PREPARED';
CREATE INDEX idx_evm_signed_submission_broadcast
    ON evm_signed_submission (broadcast_at) WHERE status = 'BROADCAST';

-- blockchain_transaction: TIMEOUT is no longer terminal. The poller keeps reading TIMEOUT rows for the
-- late-mined window and records when a receipt finally arrived; REPLACED (new status value, no CHECK
-- constraint exists on this column) means the nonce was consumed by a different transaction.
ALTER TABLE blockchain_transaction
    ADD COLUMN late_mined_at       TIMESTAMPTZ,
    ADD COLUMN replaced_by_tx_hash VARCHAR(66),
    ADD COLUMN mined_tx_hash       VARCHAR(66);

CREATE INDEX idx_btx_timeout ON blockchain_transaction (completed_at) WHERE status = 'TIMEOUT';
