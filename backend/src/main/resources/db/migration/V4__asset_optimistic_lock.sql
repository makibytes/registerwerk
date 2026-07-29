-- Optimistic-lock version for `asset`: AssetLifecycleService's submit/approve/reject/
-- issue/suspend/reactivate/redeem methods all do a read-modify-write on asset.status;
-- without a version check, two concurrent transitions can both pass their precondition
-- check and both commit silently. JPA's @Version turns a lost update into an
-- optimistic-lock failure (HTTP 409) — mirrors the identical guard already added to
-- asset_holder.version in V1 (see the eWpG §16 nominal_amount comment there).
ALTER TABLE asset
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
