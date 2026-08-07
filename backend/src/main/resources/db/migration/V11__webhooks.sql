-- Outbound event webhooks (F-BLOCKER-2, integration surface): previously there was no way for
-- a bank's core banking, treasury, or data-warehouse systems to be told anything happened —
-- every consumer had to poll REST.

CREATE TABLE webhook_subscription (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID NOT NULL,
    url          TEXT NOT NULL,
    secret       TEXT NOT NULL,
    event_types  TEXT NOT NULL, -- comma-separated WebhookEventType names; empty = all curated types
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
CREATE INDEX idx_webhook_subscription_entity ON webhook_subscription (entity_id);
CREATE INDEX idx_webhook_subscription_enabled ON webhook_subscription (entity_id, enabled);

CREATE TABLE webhook_delivery (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscription(id),
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_code   INTEGER,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_webhook_delivery_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);
CREATE INDEX idx_webhook_delivery_subscription ON webhook_delivery (subscription_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_pending ON webhook_delivery (status) WHERE status IN ('PENDING', 'FAILED');
