-- Counterparty CASP authorization register (MiCA Reg (EU) 2023/1114).
-- The EU-wide transitional period ends on 1 July 2026 (ESMA statement, 17 Apr 2026);
-- from that date, transfers to CASPs without MiCA authorization must not be executed.
-- Records are maintained by compliance officers from the ESMA / NCA registers.

CREATE TABLE casp_authorization (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vasp_did          VARCHAR(255) NOT NULL UNIQUE,
    legal_name        TEXT NOT NULL,
    lei               VARCHAR(20),
    home_member_state VARCHAR(2),
    status            VARCHAR(20) NOT NULL,
    authorization_id  TEXT,
    valid_from        DATE,
    valid_until       DATE,
    source            TEXT,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_casp_authorization_status ON casp_authorization (status);
