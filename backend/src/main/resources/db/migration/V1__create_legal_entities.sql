CREATE TABLE legal_entity (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_number        VARCHAR(30) NOT NULL UNIQUE,
    type                 VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING_ONBOARDING',
    current_name         VARCHAR(500) NOT NULL,
    lei_code             VARCHAR(20),
    registration_number  VARCHAR(100),
    registration_country CHAR(2),
    incorporation_date   DATE,
    kyc_status           VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    kyc_expiry_date      DATE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_entity_type   CHECK (type IN ('ISSUER','INVESTOR','AUDITOR')),
    CONSTRAINT chk_entity_status CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED')),
    CONSTRAINT chk_kyc_status    CHECK (kyc_status IN ('NOT_STARTED','IN_PROGRESS','APPROVED','REJECTED','EXPIRED'))
);

CREATE INDEX idx_legal_entity_type   ON legal_entity (type);
CREATE INDEX idx_legal_entity_status ON legal_entity (status);
CREATE INDEX idx_legal_entity_lei    ON legal_entity (lei_code) WHERE lei_code IS NOT NULL;

-- ── Name / M&A history ──────────────────────────────────────────────────────
CREATE TABLE entity_name_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    previous_name     VARCHAR(500) NOT NULL,
    new_name          VARCHAR(500) NOT NULL,
    change_type       VARCHAR(30) NOT NULL,
    related_entity_id UUID REFERENCES legal_entity(id),
    effective_date    DATE NOT NULL,
    notes             TEXT,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by       UUID,
    CONSTRAINT chk_name_change_type CHECK (
        change_type IN ('RENAME','MERGER_ABSORBED','MERGER_SURVIVOR','ACQUISITION')
    )
);

CREATE INDEX idx_name_history_entity ON entity_name_history (legal_entity_id);
