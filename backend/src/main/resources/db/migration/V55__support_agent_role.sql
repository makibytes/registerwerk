-- T6-05: SUPPORT_AGENT - operator support staff who may start READ_ONLY customer impersonation sessions
-- (step-up + mandatory reason + one-time handoff code) and hold no other operator privilege.
-- migration-safety: ack (CHECK constraint widened by re-adding it with SUPPORT_AGENT in the same migration; no data is dropped)
ALTER TABLE app_user DROP CONSTRAINT chk_app_user_role;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_role CHECK (
    role IN (
        'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
        'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER','SUPPORT_AGENT'
    )
);

-- migration-safety: ack (CHECK constraint widened by re-adding it with SUPPORT_AGENT in the same migration; no data is dropped)
ALTER TABLE app_user_role DROP CONSTRAINT chk_app_user_role_entry;
ALTER TABLE app_user_role ADD CONSTRAINT chk_app_user_role_entry CHECK (
    role IN (
        'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
        'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER','SUPPORT_AGENT'
    )
);
