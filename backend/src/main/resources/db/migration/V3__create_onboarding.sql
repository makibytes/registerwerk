CREATE TABLE onboarding_token (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    token_hash      VARCHAR(128) NOT NULL,    -- SHA-256 of cleartext token
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    issued_by       UUID
);

-- Only one active (unused) token per entity at a time
CREATE UNIQUE INDEX idx_onboarding_token_active
    ON onboarding_token (legal_entity_id)
    WHERE used_at IS NULL;

CREATE INDEX idx_onboarding_token_hash ON onboarding_token (token_hash);
