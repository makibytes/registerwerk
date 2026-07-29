-- Fix (finding #4, ecosystem review): operator-tier permission grants/revocations and
-- ecosystem trusted-issuer changes previously had step-up but no 4-eyes (no dual-control
-- approver persistence), unlike the comparable asset_token_admin_grant pattern.
ALTER TABLE permission_grant
    ADD COLUMN dual_control_approver_id UUID,
    ADD COLUMN dual_control_approved_at TIMESTAMPTZ;

ALTER TABLE ecosystem_trusted_issuer
    ADD COLUMN dual_control_approver_id UUID,
    ADD COLUMN dual_control_approved_at TIMESTAMPTZ;
