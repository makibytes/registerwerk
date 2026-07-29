-- Fix (Phase 5 finding #1): AssetTokenAdminGrantService.revoke() already requires 4-eyes
-- (via @RequiresStepUp on the controller) but had no column to persist the revoke-time
-- second approver — the one dual_control_approver_id/dual_control_approved_at pair on this
-- table is set at grant time and must not be overwritten by a later revoke, or the record of
-- who approved the original grant would be lost.
ALTER TABLE asset_token_admin_grant
    ADD COLUMN revoke_dual_control_approver_id UUID,
    ADD COLUMN revoke_dual_control_approved_at TIMESTAMPTZ;
