-- Phase 6 / K3: step-up MFA and login hardening (findings 6-08, 6-09, 6-10).

-- ── 6-08: single-use dual-control approver tokens ──────────────────────────────────────────────
-- One row per consumed approver token (jti). The primary key makes "use once" an atomic database
-- fact shared by every replica: the second insert of the same jti is a unique violation.
CREATE TABLE dual_control_token_use (
    jti            VARCHAR(64)  PRIMARY KEY,
    approver_id    UUID         NOT NULL,
    initiator_id   UUID         NOT NULL,
    action         VARCHAR(200) NOT NULL,
    target_digest  VARCHAR(64),
    used_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_dual_control_token_use_expires ON dual_control_token_use (expires_at);

COMMENT ON TABLE dual_control_token_use IS
    'Consumed dual-control approver tokens (K3, 6-08). A token is bound to action + target and may '
    'be redeemed once; rows are pruned after expires_at (DualControlTokenUseHousekeeping).';

-- ── 6-09: step-up MFA state shared across replicas, encrypted TOTP secret ──────────────────────
-- totp_secret now holds an envelope-encrypted value ("enc:v1:..."); legacy plaintext rows are
-- re-encrypted by TotpSecretMigration at startup and lazily on the next successful verification.
ALTER TABLE app_user ADD COLUMN totp_secret_kid VARCHAR(200);

COMMENT ON COLUMN app_user.totp_secret_kid IS
    'Name of the KekProvider that wrapped the data key of totp_secret (rotation / audit aid).';

CREATE TABLE totp_state (
    user_id             UUID        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    last_accepted_step  BIGINT      NOT NULL DEFAULT 0,
    failed_attempts     INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE totp_state IS
    'RFC 6238 replay guard (last accepted time step) and brute-force lockout of the TOTP step-up, '
    'kept in the database so every replica enforces the same state (K3, 6-09).';

-- ── 6-10: login throttling keyed on (account, source) ──────────────────────────────────────────
-- login_attempt keeps its table; keys are now typed and prefixed ("p|email|ip", "i|ip", "a|email",
-- "g|"), counters use an explicit window, and a pair lock grows exponentially per episode.
ALTER TABLE login_attempt
    ADD COLUMN kind          VARCHAR(8)  NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN window_start  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN locked_until  TIMESTAMPTZ,
    ADD COLUMN lock_episodes INT         NOT NULL DEFAULT 0;

-- Counters keyed on the bare e-mail are superseded by the typed keys.
DELETE FROM login_attempt WHERE kind = 'LEGACY';

COMMENT ON TABLE login_attempt IS
    'Shared login throttle state (LoginAttemptLimiter): PAIR (email+source IP, hard lock with '
    'exponential backoff), IP (sliding failure window), ACCOUNT (progressive delay only, never a '
    'lock) and GLOBAL. Bounded: purged by LoginAttemptPurgeJob and capped by a row-count guard.';
