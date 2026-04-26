-- Extend app_user role model with compliance-specific operator role.
-- This enables separation of duties for KYC decisions while keeping
-- REGISTRY_ADMIN as explicit override authority.

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role
    CHECK (role IN ('REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER'));
