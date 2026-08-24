-- erc3643_identity_registry.register/deleteIdentity are optimistic writes (see
-- IdentityRegistryService's javadoc) with no confirmation-gated moment before this migration —
-- unlike blockchain_transaction/asset_deployment/orgidentity's *_registration tables, nothing here
-- ever re-verified against finality, so a reorg un-mining registerIdentity/deleteIdentity could
-- leave the register asserting a state the chain no longer agrees with. These columns give
-- Erc3643IdentityRegistryConfirmationListener what it needs to close that gap: chain_config_id to
-- locate the confirming block, removed_by_tx to track the deleteIdentity call the same way
-- registered_by_tx already tracks registerIdentity, and the two *_confirmed flags to scope its
-- polling query so it shrinks over time instead of re-scanning every entry ever written.

ALTER TABLE erc3643_identity_registry ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE erc3643_identity_registry ADD COLUMN removed_by_tx VARCHAR(66);
ALTER TABLE erc3643_identity_registry ADD COLUMN registration_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE erc3643_identity_registry ADD COLUMN removal_confirmed BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_erc3643_identity_registry_pending_registration
    ON erc3643_identity_registry (registered_by_tx)
    WHERE registration_confirmed = false AND registered_by_tx IS NOT NULL;

CREATE INDEX idx_erc3643_identity_registry_pending_removal
    ON erc3643_identity_registry (removed_by_tx)
    WHERE removal_confirmed = false AND removed_by_tx IS NOT NULL;
