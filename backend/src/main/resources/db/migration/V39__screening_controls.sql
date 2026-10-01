-- Phase 6 / K7 (6-18, 6-19, 6-17 screening half): hit fingerprint + audited carry-forward of
-- accepted false positives, PEP confirmation / EDD resolution path, per-run threshold and data version.
-- Additive only. Existing hits get no fingerprint, so nothing already in the database can ever be
-- carried forward silently: the first nightly run after deployment re-opens whatever is genuinely open.

ALTER TABLE screening_run
    ADD COLUMN threshold_used NUMERIC(4,3),
    ADD COLUMN data_version   TEXT;

ALTER TABLE screening_hit
    ADD COLUMN external_id          TEXT,
    ADD COLUMN fingerprint          VARCHAR(64),
    ADD COLUMN carried_from_hit_id  UUID REFERENCES screening_hit(id),
    ADD COLUMN carried_at           TIMESTAMPTZ,
    ADD COLUMN resolution           VARCHAR(20),
    ADD COLUMN pep_confirmed_by     UUID,
    ADD COLUMN pep_confirmed_at     TIMESTAMPTZ,
    ADD COLUMN pep_confirm_note     TEXT,
    ADD COLUMN edd_approval_id      UUID,
    ADD COLUMN edd_approved_at      TIMESTAMPTZ,
    ADD COLUMN edd_review_due       TIMESTAMPTZ;

ALTER TABLE screening_hit
    ADD CONSTRAINT chk_screening_hit_resolution
        CHECK (resolution IS NULL OR resolution IN ('FALSE_POSITIVE', 'CONFIRMED_PEP'));

-- Decisions taken before this migration were all "false positive" acceptances.
UPDATE screening_hit SET resolution = 'FALSE_POSITIVE' WHERE accepted IS TRUE AND resolution IS NULL;

CREATE INDEX idx_screening_hit_fingerprint ON screening_hit (fingerprint) WHERE fingerprint IS NOT NULL;
CREATE INDEX idx_screening_run_latest_entity ON screening_run (entity_id, provider, started_at DESC) WHERE entity_id IS NOT NULL;
CREATE INDEX idx_screening_run_latest_person ON screening_run (natural_person_id, provider, started_at DESC) WHERE natural_person_id IS NOT NULL;
