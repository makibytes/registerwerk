-- ─────────────────────────────────────────────────────────────────────────────
-- App users — operator and audit accounts managed by the registry itself.
-- Used when ENTRA_ENABLED=false (built-in admin mode).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE app_user (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,                  -- BCrypt $2a$ string
    role            VARCHAR(30)  NOT NULL DEFAULT 'REGISTRY_ADMIN',
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    CONSTRAINT chk_app_user_role CHECK (role IN ('REGISTRY_ADMIN','AUDIT'))
);

CREATE INDEX idx_app_user_email_lower ON app_user (LOWER(email));
