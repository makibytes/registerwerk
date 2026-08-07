-- Relationship-manager role and client assignment (F-BLOCKER-15): previously operator staff
-- had no way to be scoped to a "my clients" subset — everyone with any staff role saw every
-- entity unfiltered, and impersonation (full customer-side mutation rights) was the only
-- "act on behalf of a client" mechanism.

ALTER TABLE app_user DROP CONSTRAINT chk_app_user_role;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_role CHECK (
    role IN (
        'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
        'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER'
    )
);

ALTER TABLE app_user_role DROP CONSTRAINT chk_app_user_role_entry;
ALTER TABLE app_user_role ADD CONSTRAINT chk_app_user_role_entry CHECK (
    role IN (
        'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
        'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER'
    )
);

ALTER TABLE legal_entity ADD COLUMN assigned_relationship_manager_id UUID;
CREATE INDEX idx_legal_entity_assigned_rm ON legal_entity (assigned_relationship_manager_id);
