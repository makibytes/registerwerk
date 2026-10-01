-- Phase 5 / K1 - trading core.
--   5A-01  the buyer no longer elects instant settlement: a per-listing seller opt-in replaces it.
--   5A-03  PAYMENT_UNRESOLVED (declared payment can no longer silently FAIL), optimistic version
--          on trade_execution (timeout job vs seller confirm lost update), operator evidence notes.
--   5A-06  buyer cool-down after a cancelled / timed-out reservation on the same listing.

-- ── 5A-01 ────────────────────────────────────────────────────────────────────
ALTER TABLE trade_listing ADD COLUMN allow_instant_settlement BOOLEAN NOT NULL DEFAULT false;

-- The buyer-owned flag is ignored by the application from now on; neutralise stored values so no
-- reader can be misled by it (column kept for API compatibility).
ALTER TABLE company_trader_settings ALTER COLUMN immediate_settlement_enabled SET DEFAULT false;
UPDATE company_trader_settings SET immediate_settlement_enabled = false WHERE immediate_settlement_enabled;

-- ── 5A-03 ────────────────────────────────────────────────────────────────────
ALTER TABLE trade_execution ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trade_execution ADD COLUMN instant_settlement BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE trade_execution ADD COLUMN dispute_reason VARCHAR(1000);
ALTER TABLE trade_execution ADD COLUMN unresolved_at TIMESTAMPTZ;
ALTER TABLE trade_execution ADD COLUMN unresolved_reason VARCHAR(1000);
ALTER TABLE trade_execution ADD COLUMN buyer_cooldown_until TIMESTAMPTZ;

-- migration-safety: ack (constraint is re-created below with the widened status set; no data removed)
ALTER TABLE trade_execution DROP CONSTRAINT chk_trade_execution_settlement_status;
ALTER TABLE trade_execution ADD CONSTRAINT chk_trade_execution_settlement_status CHECK (
    settlement_status IN ('PENDING','AWAITING_SELLER_CONFIRMATION','PAYMENT_UNRESOLVED',
                          'SETTLED','FAILED','CANCELLED','REFUNDED')
);

-- The operator queue and the unresolved-age gauge read only these rows.
CREATE INDEX idx_trade_execution_unresolved ON trade_execution (unresolved_at)
    WHERE settlement_status = 'PAYMENT_UNRESOLVED';

-- ── 5A-06 ────────────────────────────────────────────────────────────────────
-- Reservation caps (open reservations per buyer) and the per-listing cool-down lookup.
CREATE INDEX idx_trade_execution_buyer_listing_status
    ON trade_execution (buyer_entity_id, listing_id, settlement_status);
CREATE INDEX idx_trade_execution_buyer_cooldown
    ON trade_execution (buyer_entity_id, listing_id, buyer_cooldown_until)
    WHERE buyer_cooldown_until IS NOT NULL;

-- Both parties (and the operator) may add evidence notes to a disputed / unresolved trade.
-- Text only in the interim (no files, parked decision T5-02).
CREATE TABLE trade_execution_note (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id      UUID          NOT NULL REFERENCES trade_execution(id),
    actor_entity_id   UUID,
    actor_user_id     UUID,
    actor_role        VARCHAR(30)   NOT NULL,
    note_text         VARCHAR(2000) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_trade_execution_note_execution ON trade_execution_note (execution_id, created_at);
