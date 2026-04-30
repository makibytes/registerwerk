ALTER TABLE legal_entity
    ADD COLUMN idp_issuer_url VARCHAR(500),
    ADD COLUMN idp_client_id VARCHAR(255),
    ADD COLUMN idp_client_secret VARCHAR(500);

ALTER TABLE app_user
    ADD COLUMN full_name VARCHAR(200),
    ADD COLUMN legal_entity_id UUID REFERENCES legal_entity(id),
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN created_by UUID;

ALTER TABLE app_user
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE app_user
    DROP CONSTRAINT IF EXISTS chk_app_user_role;

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role CHECK (
        role IN (
            'REGISTRY_ADMIN',
            'AUDIT',
            'COMPLIANCE_OFFICER',
            'ISSUER',
            'INVESTOR',
            'COMPANY_ADMIN',
            'TRADER'
        )
    );

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_auth_provider CHECK (
        auth_provider IN ('LOCAL', 'ENTRA')
    );

CREATE INDEX idx_app_user_legal_entity_id ON app_user (legal_entity_id);

CREATE TABLE app_user_role (
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role        VARCHAR(30) NOT NULL,
    PRIMARY KEY (app_user_id, role),
    CONSTRAINT chk_app_user_role_entry CHECK (
        role IN (
            'REGISTRY_ADMIN',
            'AUDIT',
            'COMPLIANCE_OFFICER',
            'ISSUER',
            'INVESTOR',
            'COMPANY_ADMIN',
            'TRADER'
        )
    )
);

INSERT INTO app_user_role (app_user_id, role)
SELECT id, role
FROM app_user
WHERE role IS NOT NULL;

CREATE TABLE app_user_action_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(128) NOT NULL,
    token_type  VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_by  UUID,
    CONSTRAINT chk_app_user_action_token_type CHECK (
        token_type IN ('REGISTRATION', 'PASSWORD_RESET')
    )
);

CREATE UNIQUE INDEX idx_app_user_action_token_hash ON app_user_action_token (token_hash);
CREATE INDEX idx_app_user_action_token_active
    ON app_user_action_token (app_user_id, token_type)
    WHERE consumed_at IS NULL;
