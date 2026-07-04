-- V6: Optimistic-lock version column on asset_holder.
--
-- The holder's nominal_amount is the legally canonical register balance (eWpG §16).
-- It is mutated by read-modify-write in several paths that can run concurrently —
-- trade settlement, manual §24 corrections, and off-chain indexer sync. Without a
-- version guard, two concurrent updates silently lose one another (a classic lost
-- update on a securities balance). JPA's @Version turns the losing write into an
-- optimistic-lock failure, which the API surfaces as HTTP 409 for the caller to retry.
--
-- Existing rows default to version 0; Hibernate increments on each subsequent update.

ALTER TABLE asset_holder
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
