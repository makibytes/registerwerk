-- Phase 5 / K2 - trading currency, related-party controls and the venue-perimeter interim.
--   5A-02  listings and trades carry the settlement currency (and the payment rail for stablecoins);
--          the executed total is rounded to the currency's minor unit and the rounding is stored.
--   5A-06  related-party / self-dealing flag on the execution (excluded from the reference price).
--   5C-06  targeted (bilateral) listings, venue classification snapshot and RTS 22-style order
--          record fields (who submitted, when, in which classification) - interim, parked T5-06.
-- All columns are nullable / defaulted so legacy rows stay honest: a NULL currency means
-- "currency not recorded" and is never rendered as EUR.

ALTER TABLE trade_listing ADD COLUMN currency             VARCHAR(10);
ALTER TABLE trade_listing ADD COLUMN payment_rail_code    VARCHAR(40);
ALTER TABLE trade_listing ADD COLUMN target_entity_id     UUID;
ALTER TABLE trade_listing ADD COLUMN created_by_actor_id  UUID;
ALTER TABLE trade_listing ADD COLUMN venue_classification VARCHAR(20);

ALTER TABLE trade_execution ADD COLUMN currency               VARCHAR(10);
ALTER TABLE trade_execution ADD COLUMN payment_rail_code      VARCHAR(40);
-- Stored rounding: the exact product and how it was rounded to total_price.
ALTER TABLE trade_execution ADD COLUMN total_price_unrounded  NUMERIC(38,18);
ALTER TABLE trade_execution ADD COLUMN price_rounding_scale   SMALLINT;
ALTER TABLE trade_execution ADD COLUMN price_rounding_mode    VARCHAR(20);
ALTER TABLE trade_execution ADD COLUMN related_party          BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE trade_execution ADD COLUMN related_party_reasons  VARCHAR(300);
ALTER TABLE trade_execution ADD COLUMN created_by_actor_id    UUID;
ALTER TABLE trade_execution ADD COLUMN venue_classification   VARCHAR(20);

CREATE INDEX idx_trade_listing_target ON trade_listing (target_entity_id) WHERE target_entity_id IS NOT NULL;
CREATE INDEX idx_trade_listing_created ON trade_listing (created_at);
-- Reference price: latest settled trade between unrelated parties.
CREATE INDEX idx_trade_execution_reference_price ON trade_execution (asset_id, settled_at DESC)
    WHERE settlement_status = 'SETTLED' AND NOT related_party;
