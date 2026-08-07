-- Access recertification / entitlement review campaigns (Track 7-3).
--
-- BAIT (and every bank's IAM policy) requires periodic review and sign-off of user entitlements.
-- Previously there was no campaign tooling, no attestation record, and no "last reviewed"
-- timestamp on any account's roles — a REGISTRY_ADMIN's own role assignment was as unreviewed as
-- the day it was granted, no matter how long ago that was.

CREATE TABLE access_review_campaign (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    due_date    DATE,
    started_by  UUID         NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_by   UUID,
    closed_at   TIMESTAMPTZ,
    CONSTRAINT chk_access_review_campaign_status CHECK (status IN ('OPEN','CLOSED'))
);

-- One row per app_user, snapshotted at campaign start — the roles snapshot is what's actually
-- being attested to, independent of any role change the account undergoes mid-campaign.
CREATE TABLE access_review_item (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID         NOT NULL REFERENCES access_review_campaign(id) ON DELETE CASCADE,
    app_user_id         UUID         NOT NULL REFERENCES app_user(id),
    email_snapshot      VARCHAR(320) NOT NULL,
    full_name_snapshot  VARCHAR(200),
    roles_snapshot      VARCHAR(500) NOT NULL,
    decision            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by         UUID,
    reviewed_at         TIMESTAMPTZ,
    notes               TEXT,
    CONSTRAINT chk_access_review_item_decision CHECK (decision IN ('PENDING','CONFIRMED','REVOKED')),
    CONSTRAINT ux_access_review_item UNIQUE (campaign_id, app_user_id)
);

CREATE INDEX idx_access_review_item_campaign ON access_review_item (campaign_id);
CREATE INDEX idx_access_review_item_app_user ON access_review_item (app_user_id);
