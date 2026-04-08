CREATE TABLE asset (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_number     VARCHAR(30) NOT NULL UNIQUE,
    issuer_id        UUID NOT NULL REFERENCES legal_entity(id),
    name             VARCHAR(500) NOT NULL,
    isin             VARCHAR(12),
    token_standard   VARCHAR(20) NOT NULL,
    onchain_level    VARCHAR(10) NOT NULL DEFAULT 'NONE',
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    termsheet_doc_id UUID REFERENCES kyc_document(id),
    public_data      JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_token_standard CHECK (
        token_standard IN ('ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643','SPL')
    ),
    CONSTRAINT chk_onchain_level CHECK (onchain_level IN ('NONE','SIMPLE','CONTROL')),
    CONSTRAINT chk_asset_status  CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED')
    )
);

CREATE UNIQUE INDEX idx_asset_isin ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX idx_asset_issuer     ON asset (issuer_id);
CREATE INDEX idx_asset_status     ON asset (status);

-- ── On-chain deployments ────────────────────────────────────────────────────
CREATE TABLE asset_deployment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           UUID NOT NULL REFERENCES asset(id),
    chain              VARCHAR(20) NOT NULL,
    network            VARCHAR(10) NOT NULL,
    contract_address   VARCHAR(66),
    deployed_at        TIMESTAMPTZ,
    deployed_by_tx     VARCHAR(66),
    deployment_status  VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT chk_chain   CHECK (chain IN ('ETHEREUM','POLYGON','BASE','SOLANA')),
    CONSTRAINT chk_network CHECK (network IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_dep_status CHECK (deployment_status IN ('PENDING','CONFIRMED','FAILED'))
);

CREATE UNIQUE INDEX idx_deployment_address
    ON asset_deployment (chain, network, contract_address)
    WHERE contract_address IS NOT NULL;

CREATE INDEX idx_deployment_asset ON asset_deployment (asset_id);
