-- Phase 5 / K4: webhook hardening (5D-08, 5D-09).
--  * secret at rest: `secret` now holds `enc:v1:<b64>` (AES-256-GCM under a KEK-wrapped DEK);
--    rows written before this migration are re-encrypted by WebhookStartupMaintenance and are
--    read as legacy plaintext until then.
--  * rotation overlap, circuit breaker, URL-policy disable reason.
--  * delivery: stable event id, coarse outcome (the raw HTTP code is no longer exposed), and a
--    next_attempt_at schedule so the retry sweep can claim due rows with SKIP LOCKED.

ALTER TABLE webhook_subscription
    ADD COLUMN secret_previous_enc     TEXT,
    ADD COLUMN secret_rotated_at       TIMESTAMPTZ,
    ADD COLUMN key_version             INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN disabled_reason         VARCHAR(40),
    ADD COLUMN consecutive_failures    INTEGER NOT NULL DEFAULT 0;

ALTER TABLE webhook_delivery
    ADD COLUMN event_id         UUID,
    ADD COLUMN outcome          VARCHAR(20),
    ADD COLUMN next_attempt_at  TIMESTAMPTZ;

UPDATE webhook_delivery SET event_id = id WHERE event_id IS NULL;
ALTER TABLE webhook_delivery ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE webhook_delivery ALTER COLUMN event_id SET DEFAULT gen_random_uuid();

ALTER TABLE webhook_delivery
    ADD CONSTRAINT chk_webhook_delivery_outcome
        CHECK (outcome IS NULL OR outcome IN ('OK', 'RECEIVER_ERROR', 'UNREACHABLE', 'BLOCKED'));

-- Deliveries that were FAILED/PENDING but still under the attempt cap become due immediately.
UPDATE webhook_delivery SET next_attempt_at = now()
 WHERE status IN ('PENDING', 'FAILED') AND attempt_count < 8;

DROP INDEX IF EXISTS idx_webhook_delivery_pending;
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at IS NOT NULL;
