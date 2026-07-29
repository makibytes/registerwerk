-- Fix (finding #12, ERC-3643 review): erc3643_identity_registry's UNIQUE (suite_id,
-- wallet_address) constraint has no partial predicate, but removal is a soft-delete
-- (removed_at). Since registerInvestor always INSERTs a new row rather than reactivating,
-- re-registering a previously-removed wallet always hit this constraint, surfacing as an
-- unhandled 500 instead of succeeding.
ALTER TABLE erc3643_identity_registry
    DROP CONSTRAINT erc3643_identity_registry_suite_id_wallet_address_key;

CREATE UNIQUE INDEX erc3643_identity_registry_active_unique
    ON erc3643_identity_registry (suite_id, wallet_address)
    WHERE removed_at IS NULL;
