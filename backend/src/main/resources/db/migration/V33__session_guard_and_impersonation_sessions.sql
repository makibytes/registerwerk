-- Phase 6 K1 (6-01, 6-31, 6-32): per-request session guard, token revocation, impersonation sessions.

-- Tokens issued before this instant are rejected (iat < tokens_valid_after). Bumped by AppUser setters
-- on disable/enable, role, entity or password change and by SessionRevocationPort.revokeAll.
ALTER TABLE app_user ADD COLUMN tokens_valid_after TIMESTAMPTZ;

-- Individually revoked session tokens (logout, ended impersonation). Pruned once the token has expired.
CREATE TABLE session_revocation (
    jti        VARCHAR(64)  PRIMARY KEY,
    user_id    UUID,
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(40)
);
CREATE INDEX idx_session_revocation_expires ON session_revocation (expires_at);

-- One row per operator impersonation of a customer entity; id is the session token's jti.
CREATE TABLE impersonation_session (
    id                  UUID PRIMARY KEY,
    actor_id            UUID         NOT NULL,
    target_entity_id    UUID         NOT NULL,
    mode                VARCHAR(20)  NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    ticket_ref          VARCHAR(100),
    approver_id         UUID,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    ended_at            TIMESTAMPTZ,
    ended_by            UUID,
    end_reason          VARCHAR(40),
    handoff_code_hash   VARCHAR(64)  NOT NULL,
    handoff_expires_at  TIMESTAMPTZ  NOT NULL,
    handoff_consumed_at TIMESTAMPTZ,
    CONSTRAINT chk_impersonation_mode CHECK (mode IN ('READ_ONLY', 'ACT_ON_BEHALF')),
    CONSTRAINT uq_impersonation_handoff UNIQUE (handoff_code_hash)
);
CREATE INDEX idx_impersonation_session_entity ON impersonation_session (target_entity_id, started_at DESC);
CREATE INDEX idx_impersonation_session_open ON impersonation_session (expires_at) WHERE ended_at IS NULL;
