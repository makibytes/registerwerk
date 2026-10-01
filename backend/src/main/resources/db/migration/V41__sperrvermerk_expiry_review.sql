-- Phase 6 K9 (6-25, parked T6-11): Sperrvermerk lifecycle.
--
-- A block past expires_at is no longer lifted silently at 03:00 for every type. Types not listed in
-- registerwerk.sperrvermerk.auto-expire-types (default: none) move to the blocking status EXPIRY_REVIEW and
-- stay enforced until compliance lifts them with step-up + second approver. holder_block.status has no CHECK
-- constraint, so the new value needs no DDL.
ALTER TABLE holder_block
    ADD COLUMN expiry_confirmed_by_approver BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN expiry_review_at             TIMESTAMPTZ;

COMMENT ON COLUMN holder_block.expiry_confirmed_by_approver IS
    'Second approver confirmed the expires_at date against the court/authority order at creation (legal-order types).';
COMMENT ON COLUMN holder_block.expiry_review_at IS
    'When the block passed expires_at and was moved to EXPIRY_REVIEW (still blocking).';

-- Rollout step: rows that are ACTIVE with a past expires_at today would be auto-lifted by the first 03:00 run
-- after deploy. Move them to EXPIRY_REVIEW instead so nothing is released without a human confirmation.
UPDATE holder_block
   SET status = 'EXPIRY_REVIEW', expiry_review_at = now(), updated_at = now()
 WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= now();

-- EXPIRY_REVIEW blocks are enforced by every gate; the lookups need the same partial indexes as ACTIVE.
CREATE INDEX idx_holder_block_wallet_review ON holder_block (wallet_address) WHERE status = 'EXPIRY_REVIEW';
CREATE INDEX idx_holder_block_entity_review ON holder_block (entity_id)      WHERE status = 'EXPIRY_REVIEW';
