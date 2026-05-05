CREATE TABLE company_external_reference (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    subject_type          VARCHAR(50) NOT NULL,
    subject_id            UUID NOT NULL,
    external_id           VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID,
    CONSTRAINT uq_company_external_reference_subject
        UNIQUE (owner_legal_entity_id, subject_type, subject_id),
    CONSTRAINT chk_company_external_reference_subject_type
        CHECK (subject_type IN (
            'LEGAL_ENTITY',
            'ASSET',
            'ASSET_HOLDER',
            'ERC3643_IDENTITY_REGISTRY_ENTRY'
        ))
);

CREATE INDEX idx_company_external_reference_owner
    ON company_external_reference (owner_legal_entity_id);

CREATE INDEX idx_company_external_reference_lookup
    ON company_external_reference (owner_legal_entity_id, external_id);

CREATE INDEX idx_company_external_reference_owner_type
    ON company_external_reference (owner_legal_entity_id, subject_type, updated_at DESC);
