-- Metadata table (no binary data here — kept lean for list queries)
CREATE TABLE kyc_document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    document_type   VARCHAR(30) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    storage_ref     VARCHAR(1000) NOT NULL,   -- 'inline' or S3 key
    content_hash    VARCHAR(64) NOT NULL,     -- SHA-256 hex
    size_bytes      BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_by     UUID,
    expires_at      DATE,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT chk_doc_type CHECK (
        document_type IN (
            'PASSPORT','COMMERCIAL_REGISTER','ANNUAL_REPORT',
            'OWNERSHIP_CHART','AML_QUESTIONNAIRE','OTHER'
        )
    ),
    CONSTRAINT chk_mime CHECK (
        mime_type IN (
            'application/pdf','image/jpeg','image/png',
            'image/tiff','application/xml','text/xml',
            'application/octet-stream'
        )
    )
);

CREATE INDEX idx_kyc_doc_entity ON kyc_document (legal_entity_id);
CREATE INDEX idx_kyc_doc_type   ON kyc_document (legal_entity_id, document_type);

-- Binary content stored separately so metadata scans never load blobs
CREATE TABLE kyc_document_content (
    id      UUID PRIMARY KEY REFERENCES kyc_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);
