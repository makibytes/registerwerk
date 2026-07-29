-- Fix (HIGH): HolderService.removeHolder previously hard-deleted asset_holder rows with
-- zero audit trail. A §16 eWpG register entry disappearing entirely conflicts with
-- retention/tamper-evidence obligations, so removal is now a soft-delete: the row stays,
-- removed_at records when (and, via the accompanying HolderRemovedEvent, who) closed it out.
ALTER TABLE asset_holder
    ADD COLUMN removed_at TIMESTAMPTZ;

COMMENT ON COLUMN asset_holder.removed_at IS
    'Soft-delete marker. NULL = still an active register entry. Non-null = the instant '
    'HolderService.removeHolder closed this holder out; the row is retained (never hard-deleted) '
    'for eWpG §16 retention/tamper-evidence. Compliance-facing reads should exclude removed rows '
    '(see AssetHolderRepository.findActive* methods); reconciliation/audit reads may still need them.';

-- Compliance-facing listings filter on this predicate constantly (findActiveByAssetId /
-- findActiveByInvestorId / existsActiveBy...); index it alongside the existing lookup columns.
CREATE INDEX idx_holder_asset_active ON asset_holder (asset_id) WHERE removed_at IS NULL;
CREATE INDEX idx_holder_investor_active ON asset_holder (investor_id) WHERE removed_at IS NULL;
