-- Phase 6 K2 (6-02..6-06): user lifecycle, access review, identity binding.

-- Who last changed this account's roles (reviewer independence in access review) and when
-- (campaign-completeness check at close). Creation counts through created_at.
ALTER TABLE app_user ADD COLUMN roles_changed_by UUID;
ALTER TABLE app_user ADD COLUMN roles_changed_at TIMESTAMPTZ;

-- Set on the account DefaultAdminSeeder creates; cleared when the password is changed through a
-- reset token. ProductionReadinessCheck fails production boots that still carry it after 24 h.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- Access-review items: campaign-native 4-eyes (REVOKE_PROPOSED), staleness (STALE), SoD warning.
-- migration-safety: ack (CHECK constraint is re-created immediately below with a strictly wider value set; no data is dropped)
ALTER TABLE access_review_item DROP CONSTRAINT chk_access_review_item_decision;
ALTER TABLE access_review_item ADD CONSTRAINT chk_access_review_item_decision
    CHECK (decision IN ('PENDING','CONFIRMED','REVOKED','REVOKE_PROPOSED','STALE'));
ALTER TABLE access_review_item ADD COLUMN enabled_snapshot BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE access_review_item ADD COLUMN proposed_by UUID;
ALTER TABLE access_review_item ADD COLUMN proposed_at TIMESTAMPTZ;
ALTER TABLE access_review_item ADD COLUMN sod_conflicts VARCHAR(300);
ALTER TABLE access_review_item ADD COLUMN reopened_count INTEGER NOT NULL DEFAULT 0;
