-- ─── Asset document storage ──────────────────────────────────────────────────
-- Stores term sheets and other regulatory documents attached to an asset.
-- Documents can originate from a direct upload (UPLOAD) or be fetched from the
-- deployed token contract via ERC-1643 (ONCHAIN_ERC1643), tokenURI/uri()
-- (ONCHAIN_TOKEN_URI), or Solana Metaplex metadata (ONCHAIN_SOLANA).
-- Content is stored inline (≤ 5 MB) or in S3 (> 5 MB), mirroring kyc_document.

CREATE TABLE asset_document (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID         NOT NULL REFERENCES asset(id),
    document_type    VARCHAR(30)  NOT NULL DEFAULT 'TERM_SHEET',
    source           VARCHAR(30)  NOT NULL DEFAULT 'UPLOAD',
    mime_type        VARCHAR(100) NOT NULL,
    file_name        VARCHAR(500),
    -- 'inline' = content is in asset_document_content; otherwise S3 object key.
    -- NULL when on-chain reference is recorded but content has not yet been fetched.
    storage_ref      VARCHAR(1000),
    -- SHA-256 hex for uploaded content; keccak256 hex for on-chain verified content.
    content_hash     VARCHAR(66),
    size_bytes       BIGINT,
    -- On-chain provenance (NULL for UPLOAD source)
    chain            VARCHAR(20),
    network          VARCHAR(10),
    onchain_doc_name VARCHAR(66),    -- bytes32 document name as 0x-prefixed hex
    onchain_uri      VARCHAR(2000),  -- URI returned by the contract
    -- Timestamps
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by      UUID,
    fetched_at       TIMESTAMPTZ, -- when content was last downloaded from onchain_uri
    deleted_at       TIMESTAMPTZ
);

CREATE TABLE asset_document_content (
    id      UUID  PRIMARY KEY REFERENCES asset_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);

CREATE INDEX idx_asset_doc_asset_type
    ON asset_document(asset_id, document_type)
    WHERE deleted_at IS NULL;
