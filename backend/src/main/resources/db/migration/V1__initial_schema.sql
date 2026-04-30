-- ─────────────────────────────────────────────────────────────────────────────
-- Initial schema — squashed from V1-V8.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Legal entities ──────────────────────────────────────────────────────────
CREATE TABLE legal_entity (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_number        VARCHAR(30) NOT NULL UNIQUE,
    type                 VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING_ONBOARDING',
    current_name         VARCHAR(500) NOT NULL,
    lei_code             VARCHAR(20),
    registration_number  VARCHAR(100),
    registration_country VARCHAR(2),
    incorporation_date   DATE,
    kyc_status           VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    kyc_expiry_date      DATE,
    idp_issuer_url       VARCHAR(500),
    idp_client_id        VARCHAR(255),
    idp_client_secret    VARCHAR(500),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_entity_type   CHECK (type IN ('ISSUER','INVESTOR','AUDITOR')),
    CONSTRAINT chk_entity_status CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED')),
    CONSTRAINT chk_kyc_status    CHECK (kyc_status IN ('NOT_STARTED','IN_PROGRESS','APPROVED','REJECTED','EXPIRED'))
);

CREATE INDEX idx_legal_entity_type   ON legal_entity (type);
CREATE INDEX idx_legal_entity_status ON legal_entity (status);
CREATE INDEX idx_legal_entity_lei    ON legal_entity (lei_code) WHERE lei_code IS NOT NULL;

-- ── Name / M&A history ──────────────────────────────────────────────────────
CREATE TABLE entity_name_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    previous_name     VARCHAR(500) NOT NULL,
    new_name          VARCHAR(500) NOT NULL,
    change_type       VARCHAR(30) NOT NULL,
    related_entity_id UUID REFERENCES legal_entity(id),
    effective_date    DATE NOT NULL,
    notes             TEXT,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by       UUID,
    CONSTRAINT chk_name_change_type CHECK (
        change_type IN ('RENAME','MERGER_ABSORBED','MERGER_SURVIVOR','ACQUISITION')
    )
);

CREATE INDEX idx_name_history_entity ON entity_name_history (legal_entity_id);

-- ── Entity merge records ────────────────────────────────────────────────────
CREATE TABLE entity_merge_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    merge_type       VARCHAR(20) NOT NULL DEFAULT 'ABSORPTION',
    effective_date   DATE NOT NULL,
    notes            TEXT,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by      UUID,
    CONSTRAINT chk_merge_type CHECK (merge_type IN ('ABSORPTION','CONSOLIDATION')),
    CONSTRAINT chk_no_self_merge CHECK (source_entity_id != target_entity_id)
);

CREATE INDEX idx_merge_source ON entity_merge_record (source_entity_id);
CREATE INDEX idx_merge_target ON entity_merge_record (target_entity_id);

-- ── KYC documents ───────────────────────────────────────────────────────────
CREATE TABLE kyc_document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    document_type   VARCHAR(64) NOT NULL,
    jurisdiction    VARCHAR(20),
    mime_type       VARCHAR(100) NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    storage_ref     VARCHAR(1000) NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_by     UUID,
    expires_at      DATE,
    deleted_at      TIMESTAMPTZ,
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

CREATE TABLE kyc_document_content (
    id      UUID PRIMARY KEY REFERENCES kyc_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);

CREATE TABLE kyc_jurisdiction_approval (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID NOT NULL REFERENCES legal_entity(id),
    jurisdiction     VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by      UUID,
    approved_at      TIMESTAMPTZ,
    expires_at       DATE,
    rejection_reason TEXT,
    override_note    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, jurisdiction)
);

CREATE INDEX idx_kyc_jur_entity ON kyc_jurisdiction_approval(entity_id);

-- ── Onboarding tokens ───────────────────────────────────────────────────────
CREATE TABLE onboarding_token (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    token_hash      VARCHAR(128) NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    issued_by       UUID
);

CREATE UNIQUE INDEX idx_onboarding_token_active
    ON onboarding_token (legal_entity_id)
    WHERE used_at IS NULL;

CREATE INDEX idx_onboarding_token_hash ON onboarding_token (token_hash);

-- ── App users ───────────────────────────────────────────────────────────────
CREATE TABLE app_user (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   VARCHAR(100),
    full_name       VARCHAR(200),
    role            VARCHAR(30)  NOT NULL DEFAULT 'REGISTRY_ADMIN',
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    legal_entity_id UUID REFERENCES legal_entity(id),
    auth_provider   VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    created_by      UUID,
    CONSTRAINT chk_app_user_role CHECK (
        role IN (
            'REGISTRY_ADMIN',
            'AUDIT',
            'COMPLIANCE_OFFICER',
            'ISSUER',
            'INVESTOR',
            'COMPANY_ADMIN',
            'TRADER'
        )
    ),
    CONSTRAINT chk_app_user_auth_provider CHECK (
        auth_provider IN ('LOCAL', 'ENTRA')
    )
);

CREATE INDEX idx_app_user_email_lower     ON app_user (LOWER(email));
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

-- ── Assets ──────────────────────────────────────────────────────────────────
CREATE TABLE asset (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_number     VARCHAR(30) NOT NULL UNIQUE,
    issuer_id        UUID NOT NULL REFERENCES legal_entity(id),
    name             VARCHAR(500) NOT NULL,
    isin             VARCHAR(12),
    token_standard   VARCHAR(20) NOT NULL,
    onchain_level    VARCHAR(10) NOT NULL DEFAULT 'NONE',
    jurisdiction     VARCHAR(20),
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    termsheet_doc_id UUID REFERENCES kyc_document(id),
    public_data      JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_holder_sync_time TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_token_standard CHECK (
        token_standard IN ('ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643','SPL')
    ),
    CONSTRAINT chk_onchain_level CHECK (onchain_level IN ('NONE','SIMPLE','CONTROL')),
    CONSTRAINT chk_asset_status  CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED')
    )
);

CREATE UNIQUE INDEX idx_asset_isin       ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX        idx_asset_isin_btree ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX        idx_asset_issuer     ON asset (issuer_id);
CREATE INDEX        idx_asset_status     ON asset (status);
CREATE INDEX        idx_asset_public_data ON asset USING GIN (public_data) WHERE public_data IS NOT NULL;
CREATE INDEX        idx_asset_last_holder_sync_time ON asset(last_holder_sync_time DESC NULLS LAST);

-- ── Asset documents ─────────────────────────────────────────────────────────
CREATE TABLE asset_document (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID         NOT NULL REFERENCES asset(id),
    document_type    VARCHAR(30)  NOT NULL DEFAULT 'TERM_SHEET',
    source           VARCHAR(30)  NOT NULL DEFAULT 'UPLOAD',
    mime_type        VARCHAR(100) NOT NULL,
    file_name        VARCHAR(500),
    storage_ref      VARCHAR(1000),
    content_hash     VARCHAR(66),
    size_bytes       BIGINT,
    chain            VARCHAR(20),
    network          VARCHAR(10),
    onchain_doc_name VARCHAR(66),
    onchain_uri      VARCHAR(2000),
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by      UUID,
    fetched_at       TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ
);

CREATE TABLE asset_document_content (
    id      UUID PRIMARY KEY REFERENCES asset_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);

CREATE INDEX idx_asset_doc_asset_type
    ON asset_document(asset_id, document_type)
    WHERE deleted_at IS NULL;

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

-- ── Asset holders ───────────────────────────────────────────────────────────
CREATE TABLE asset_holder (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id          UUID NOT NULL REFERENCES asset(id),
    investor_id       UUID NOT NULL REFERENCES legal_entity(id),
    wallet_address    VARCHAR(66) NOT NULL,
    whitelisted       BOOLEAN NOT NULL DEFAULT false,
    whitelist_tx_hash VARCHAR(66),
    nominal_amount    NUMERIC(38, 18) NOT NULL DEFAULT 0,
    acquisition_date  DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_holder_wallet   ON asset_holder (asset_id, wallet_address);
CREATE INDEX        idx_holder_investor ON asset_holder (investor_id);
CREATE INDEX        idx_holder_asset    ON asset_holder (asset_id);

-- ── Mint control rules ──────────────────────────────────────────────────────
CREATE TABLE mint_control_rule (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    target_address       VARCHAR(66) NOT NULL,
    rule_type            VARCHAR(30) NOT NULL,
    max_amount           NUMERIC(38, 18),
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_rule_type CHECK (
        rule_type IN ('MINT_ALLOWANCE','AUTO_APPROVE_TRANSFER','AUTO_APPROVE_BURN')
    )
);

CREATE INDEX idx_mint_rule_deployment ON mint_control_rule (asset_deployment_id);
CREATE INDEX idx_mint_rule_target     ON mint_control_rule (target_address);

-- ── Audit log (partitioned) ─────────────────────────────────────────────────
CREATE TABLE audit_event (
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id   UUID NOT NULL,
    actor_id     UUID,
    actor_role   VARCHAR(30),
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit_event_2026_04
    PARTITION OF audit_event
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE audit_event_2026_05
    PARTITION OF audit_event
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE audit_event_default
    PARTITION OF audit_event DEFAULT;

CREATE INDEX idx_audit_subject    ON audit_event (subject_type, subject_id);
CREATE INDEX idx_audit_actor      ON audit_event (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_event_type ON audit_event (event_type);
CREATE INDEX idx_audit_payload    ON audit_event USING GIN (payload);

-- ── Chain registry ──────────────────────────────────────────────────────────
CREATE TABLE chain_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier          VARCHAR(50)  NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    chain_type          VARCHAR(10)  NOT NULL,
    network_type        VARCHAR(10)  NOT NULL,
    chain_id            BIGINT,
    rpc_url             VARCHAR(500) NOT NULL,
    ws_url              VARCHAR(500),
    fallback_rpc_urls   TEXT,                            -- comma-separated
    block_explorer_url  VARCHAR(200),
    graph_node_url      VARCHAR(300),
    graph_subgraph_name VARCHAR(100),
    enabled             BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_chain_type   CHECK (chain_type   IN ('EVM', 'SOLANA')),
    CONSTRAINT chk_network_type CHECK (network_type IN ('MAINNET', 'TESTNET'))
);

CREATE INDEX idx_chain_config_type    ON chain_config (chain_type, network_type);
CREATE INDEX idx_chain_config_enabled ON chain_config (enabled);

INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES

('ETHEREUM_MAINNET', 'Ethereum Mainnet', 'EVM', 'MAINNET', 1,
 'https://mainnet.infura.io/v3/changeme', 'wss://mainnet.infura.io/ws/v3/changeme', 'https://etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-mainnet'),

('ETHEREUM_SEPOLIA', 'Ethereum Sepolia', 'EVM', 'TESTNET', 11155111,
 'https://sepolia.infura.io/v3/changeme', 'wss://sepolia.infura.io/ws/v3/changeme', 'https://sepolia.etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-sepolia'),

('POLYGON_MAINNET', 'Polygon Mainnet', 'EVM', 'MAINNET', 137,
 'https://polygon-mainnet.infura.io/v3/changeme', 'wss://polygon-mainnet.infura.io/ws/v3/changeme', 'https://polygonscan.com',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-mainnet'),

('POLYGON_AMOY', 'Polygon Amoy Testnet', 'EVM', 'TESTNET', 80002,
 'https://polygon-amoy.infura.io/v3/changeme', 'wss://polygon-amoy.infura.io/ws/v3/changeme', 'https://amoy.polygonscan.com',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-amoy'),

('BASE_MAINNET', 'Base Mainnet', 'EVM', 'MAINNET', 8453,
 'https://mainnet.base.org', 'wss://mainnet.base.org', 'https://basescan.org',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-mainnet'),

('BASE_SEPOLIA', 'Base Sepolia Testnet', 'EVM', 'TESTNET', 84532,
 'https://sepolia.base.org', 'wss://sepolia.base.org', 'https://sepolia.basescan.org',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-sepolia'),

('SOLANA_MAINNET', 'Solana Mainnet Beta', 'SOLANA', 'MAINNET', NULL,
 'https://api.mainnet-beta.solana.com', 'wss://api.mainnet-beta.solana.com', 'https://solscan.io',
 NULL, NULL),

('SOLANA_DEVNET', 'Solana Devnet', 'SOLANA', 'TESTNET', NULL,
 'https://api.devnet.solana.com', 'wss://api.devnet.solana.com', 'https://solscan.io',
 NULL, NULL),

('FHENIX_MAINNET', 'Fhenix Mainnet', 'EVM', 'MAINNET', 21888,
 'https://api.fhenix.zone:7747', 'wss://api.fhenix.zone:7748', 'https://explorer.fhenix.zone',
 NULL, NULL),

('FHENIX_HELIUM', 'Fhenix Helium Testnet', 'EVM', 'TESTNET', 8008135,
 'https://api.helium.fhenix.zone:7747', 'wss://api.helium.fhenix.zone:7748', 'https://explorer.helium.fhenix.zone',
 NULL, NULL),

('INCO_MAINNET', 'Inco Mainnet', 'EVM', 'MAINNET', 9090,
 'https://mainnet.inco.org', 'wss://mainnet.inco.org', 'https://explorer.inco.org',
 NULL, NULL),

('INCO_RIVEST', 'Inco Rivest Testnet', 'EVM', 'TESTNET', 21097,
 'https://validator.rivest.inco.org', 'wss://validator.rivest.inco.org', 'https://explorer.rivest.inco.org',
 NULL, NULL);

-- ── RPC nodes ───────────────────────────────────────────────────────────────
CREATE TABLE rpc_node (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chain_config_id        UUID        NOT NULL REFERENCES chain_config(id) ON DELETE CASCADE,
    url                    VARCHAR(512) NOT NULL,
    label                  VARCHAR(100),
    enabled                BOOLEAN     NOT NULL DEFAULT true,
    exclusive              BOOLEAN     NOT NULL DEFAULT false,
    latest_block_number    BIGINT,
    block_last_advanced_at TIMESTAMPTZ,
    last_checked_at        TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    healthy                BOOLEAN     NOT NULL DEFAULT false,
    consecutive_failures   INT         NOT NULL DEFAULT 0,
    lag_from_best          INT,
    syncing                BOOLEAN     NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rpc_node_chain  ON rpc_node (chain_config_id);
CREATE INDEX idx_rpc_node_usable ON rpc_node (chain_config_id, enabled, healthy);

INSERT INTO rpc_node (chain_config_id, url, label, enabled)
SELECT id, rpc_url, 'Primary', true
FROM chain_config
WHERE rpc_url IS NOT NULL AND rpc_url <> '';

-- ── Token transfer history ──────────────────────────────────────────────────
CREATE TABLE token_transfer (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID        REFERENCES asset(id),
    deployment_id    UUID        REFERENCES asset_deployment(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    contract_address VARCHAR(66) NOT NULL,
    from_address     VARCHAR(66) NOT NULL,
    to_address       VARCHAR(66) NOT NULL,
    token_id         NUMERIC,
    amount           NUMERIC(38, 18),
    event_type       VARCHAR(10) NOT NULL,
    tx_hash          VARCHAR(66) NOT NULL,
    block_number     BIGINT      NOT NULL,
    log_index        INT,
    slot             BIGINT,
    occurred_at      TIMESTAMPTZ NOT NULL,
    explorer_tx_url  VARCHAR(600),
    raw_data         JSONB,
    CONSTRAINT chk_event_type CHECK (event_type IN ('MINT', 'TRANSFER', 'BURN')),
    CONSTRAINT uq_transfer_evm    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index),
    CONSTRAINT uq_transfer_solana UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot)
);

CREATE INDEX idx_transfer_asset       ON token_transfer (asset_id);
CREATE INDEX idx_transfer_deployment  ON token_transfer (deployment_id);
CREATE INDEX idx_transfer_chain       ON token_transfer (chain_config_id, block_number DESC);
CREATE INDEX idx_transfer_contract    ON token_transfer (contract_address, occurred_at DESC);
CREATE INDEX idx_transfer_from        ON token_transfer (from_address);
CREATE INDEX idx_transfer_to          ON token_transfer (to_address);
CREATE INDEX idx_transfer_event_type  ON token_transfer (event_type);

-- ── Indexer state ───────────────────────────────────────────────────────────
CREATE TABLE indexer_state (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id          UUID        NOT NULL REFERENCES chain_config(id),
    indexer_type             VARCHAR(30) NOT NULL,
    last_synced_block        BIGINT,
    last_synced_signature    VARCHAR(100),
    last_synced_at           TIMESTAMPTZ,
    last_error               TEXT,
    consecutive_errors       INT         NOT NULL DEFAULT 0,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_indexer_state UNIQUE (chain_config_id, indexer_type),
    CONSTRAINT chk_indexer_type CHECK (indexer_type IN ('GRAPH_NODE', 'SOLANA_GEYSER', 'SOLANA_POLL')),
    CONSTRAINT chk_indexer_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR'))
);

CREATE INDEX idx_indexer_state_chain  ON indexer_state (chain_config_id);
CREATE INDEX idx_indexer_state_status ON indexer_state (status);

-- ── ONCHAINID ───────────────────────────────────────────────────────────────
CREATE TABLE onchain_identity (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id  UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    identity_address VARCHAR(66) NOT NULL,
    deployed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deployed_by_tx   VARCHAR(66),
    UNIQUE (legal_entity_id, chain_config_id)
);

CREATE INDEX idx_onchain_identity_entity ON onchain_identity (legal_entity_id);
CREATE INDEX idx_onchain_identity_chain  ON onchain_identity (chain_config_id);

CREATE TABLE onchain_claim (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    onchain_identity_id UUID        NOT NULL REFERENCES onchain_identity(id),
    topic               BIGINT      NOT NULL,
    topic_label         VARCHAR(50),
    issuer_address      VARCHAR(66) NOT NULL,
    claim_id            VARCHAR(66),
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    tx_hash             VARCHAR(66),
    claim_data          TEXT,
    claim_signature     TEXT
);

CREATE INDEX idx_claim_identity ON onchain_claim (onchain_identity_id);
CREATE INDEX idx_claim_topic    ON onchain_claim (topic);

-- ── ERC-3643 (T-REX) suite ──────────────────────────────────────────────────
CREATE TABLE erc3643_suite (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id        UUID        NOT NULL UNIQUE REFERENCES asset_deployment(id),
    token_address              VARCHAR(66),
    identity_registry_address  VARCHAR(66),
    identity_registry_storage  VARCHAR(66),
    compliance_address         VARCHAR(66),
    claim_topics_registry      VARCHAR(66),
    trusted_issuers_registry   VARCHAR(66),
    factory_tx_hash            VARCHAR(66),
    is_confidential            BOOLEAN     NOT NULL DEFAULT false,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE erc3643_compliance_module (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id           UUID        NOT NULL REFERENCES erc3643_suite(id),
    module_address     VARCHAR(66) NOT NULL,
    module_type        VARCHAR(50) NOT NULL,
    parameters         JSONB,
    max_investors      INTEGER,
    max_balance        NUMERIC(38,0),
    transfer_cooldown  INTEGER,
    blocked_countries  SMALLINT[],
    added_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at         TIMESTAMPTZ
);

CREATE TABLE erc3643_trusted_issuer (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id         UUID        NOT NULL REFERENCES erc3643_suite(id),
    issuer_address   VARCHAR(66) NOT NULL,
    claim_topics     BIGINT[]    NOT NULL DEFAULT '{}',
    legal_entity_id  UUID        REFERENCES legal_entity(id),
    added_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at       TIMESTAMPTZ
);

CREATE TABLE erc3643_claim_topic (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id  UUID        NOT NULL REFERENCES erc3643_suite(id),
    topic     BIGINT      NOT NULL,
    label     VARCHAR(50),
    UNIQUE (suite_id, topic)
);

-- ── ERC-3643 identity registry mirror ───────────────────────────────────────
CREATE TABLE erc3643_identity_registry (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id             UUID        NOT NULL REFERENCES erc3643_suite(id),
    wallet_address       VARCHAR(66) NOT NULL,
    onchain_identity_id  UUID        NOT NULL REFERENCES onchain_identity(id),
    country_code         SMALLINT,
    registered_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by_tx     VARCHAR(66),
    removed_at           TIMESTAMPTZ,
    UNIQUE (suite_id, wallet_address)
);

CREATE INDEX idx_erc3643_ir_suite    ON erc3643_identity_registry (suite_id);
CREATE INDEX idx_erc3643_ir_identity ON erc3643_identity_registry (onchain_identity_id);

-- ── Blockchain transactions ─────────────────────────────────────────────────
CREATE TABLE blockchain_transaction (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tx_hash          VARCHAR(66),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    chain            VARCHAR(30),
    network          VARCHAR(30),
    contract_address VARCHAR(42),
    deployment_id    UUID,
    asset_id         UUID,
    method_name      VARCHAR(100),
    params           JSONB,
    actor_name       VARCHAR(255),
    actor_role       VARCHAR(30),
    gas_used         BIGINT,
    block_number     BIGINT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_btx_deployment ON blockchain_transaction (deployment_id);
CREATE INDEX idx_btx_asset      ON blockchain_transaction (asset_id);
CREATE INDEX idx_btx_actor      ON blockchain_transaction (actor_name);
CREATE INDEX idx_btx_status     ON blockchain_transaction (status) WHERE status = 'PENDING';
CREATE INDEX idx_btx_created    ON blockchain_transaction (created_at DESC);

-- ── Operator wallets ─────────────────────────────────────────────────────────
CREATE TABLE operator_wallet (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(120) NOT NULL UNIQUE,
    type            VARCHAR(10)  NOT NULL,
    address         VARCHAR(64)  NOT NULL,
    keystore_path   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    CONSTRAINT chk_wallet_type CHECK (type IN ('EVM','SOLANA')),
    CONSTRAINT uq_wallet_addr  UNIQUE (type, address)
);

CREATE TABLE wallet_chain_default (
    chain_config_id UUID        PRIMARY KEY REFERENCES chain_config(id) ON DELETE CASCADE,
    wallet_id       UUID        NOT NULL REFERENCES operator_wallet(id) ON DELETE RESTRICT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID
);

CREATE INDEX idx_wallet_chain_default_wallet ON wallet_chain_default (wallet_id);

-- ── Address endpoints ────────────────────────────────────────────────────────
CREATE TABLE address_endpoint (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(10)  NOT NULL,
    owner_id     UUID,
    address      VARCHAR(66)  NOT NULL,
    address_type VARCHAR(10)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    notes        VARCHAR(500),
    risk_level   VARCHAR(10),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_risk_level CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE UNIQUE INDEX idx_endpoint_entity_address
    ON address_endpoint (owner_type, owner_id, address)
    WHERE owner_id IS NOT NULL;

CREATE UNIQUE INDEX idx_endpoint_operator_address
    ON address_endpoint (owner_type, address)
    WHERE owner_id IS NULL;

CREATE INDEX idx_endpoint_owner   ON address_endpoint (owner_type, owner_id);
CREATE INDEX idx_endpoint_address ON address_endpoint (address);

-- ── Trading ──────────────────────────────────────────────────────────────────
CREATE TABLE company_trader_settings (
    legal_entity_id                UUID PRIMARY KEY REFERENCES legal_entity(id) ON DELETE CASCADE,
    default_payment_option         VARCHAR(30)  NOT NULL DEFAULT 'OFFCHAIN_SEPA',
    immediate_settlement_enabled   BOOLEAN      NOT NULL DEFAULT true,
    updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by                     UUID,
    CONSTRAINT chk_trader_default_payment_option CHECK (
        default_payment_option IN (
            'NATIVE_CHAIN_CURRENCY',
            'STABLECOIN',
            'CBMT',
            'PONTES_TARGET',
            'OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE company_trader_wallet_default (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID         NOT NULL REFERENCES legal_entity(id) ON DELETE CASCADE,
    asset_type      VARCHAR(20),
    target_type     VARCHAR(20)  NOT NULL,
    endpoint_id     UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address  VARCHAR(128),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_trader_wallet_asset_type CHECK (
        asset_type IS NULL OR asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trader_wallet_target_type CHECK (
        target_type IN ('ENDPOINT','CUSTOM_ADDRESS')
    ),
    CONSTRAINT chk_trader_wallet_target_payload CHECK (
        (target_type = 'ENDPOINT' AND endpoint_id IS NOT NULL AND wallet_address IS NULL)
        OR
        (target_type = 'CUSTOM_ADDRESS' AND endpoint_id IS NULL AND wallet_address IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_trader_wallet_default_global
    ON company_trader_wallet_default (legal_entity_id)
    WHERE asset_type IS NULL;

CREATE UNIQUE INDEX idx_trader_wallet_default_asset_type
    ON company_trader_wallet_default (legal_entity_id, asset_type)
    WHERE asset_type IS NOT NULL;

CREATE INDEX idx_trader_wallet_default_entity
    ON company_trader_wallet_default (legal_entity_id);

CREATE TABLE trade_listing (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_code         VARCHAR(20)     NOT NULL,
    seller_entity_id   UUID            NOT NULL REFERENCES legal_entity(id),
    seller_holder_id   UUID            NOT NULL REFERENCES asset_holder(id),
    asset_id           UUID            NOT NULL REFERENCES asset(id),
    asset_number       VARCHAR(30)     NOT NULL,
    asset_name         VARCHAR(500)    NOT NULL,
    isin               VARCHAR(12),
    asset_type         VARCHAR(20)     NOT NULL,
    token_standard     VARCHAR(20)     NOT NULL,
    chain              VARCHAR(20),
    status             VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    quantity_total     NUMERIC(38, 18) NOT NULL,
    quantity_available NUMERIC(38, 18) NOT NULL,
    price_per_unit     NUMERIC(38, 18) NOT NULL,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_trade_listing_venue CHECK (
        venue_code IN ('SIMULATED', 'ASSETERA', 'ARCHAX', 'TALOS')
    ),
    CONSTRAINT chk_trade_listing_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_listing_status CHECK (
        status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')
    ),
    CONSTRAINT chk_trade_listing_token_standard CHECK (
        token_standard IN ('ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643','SPL')
    ),
    CONSTRAINT chk_trade_listing_chain CHECK (
        chain IS NULL OR chain IN ('ETHEREUM','POLYGON','BASE','SOLANA')
    ),
    CONSTRAINT chk_trade_listing_quantity CHECK (
        quantity_total > 0
        AND quantity_available >= 0
        AND quantity_available <= quantity_total
    ),
    CONSTRAINT chk_trade_listing_price CHECK (price_per_unit > 0)
);

CREATE INDEX idx_trade_listing_status_created ON trade_listing (status, created_at DESC);
CREATE INDEX idx_trade_listing_seller         ON trade_listing (seller_entity_id, created_at DESC);
CREATE INDEX idx_trade_listing_asset          ON trade_listing (asset_id, created_at DESC);
CREATE INDEX idx_trade_listing_holder         ON trade_listing (seller_holder_id);

CREATE TABLE trade_listing_payment_option (
    trade_listing_id UUID        NOT NULL REFERENCES trade_listing(id) ON DELETE CASCADE,
    payment_option   VARCHAR(30) NOT NULL,
    PRIMARY KEY (trade_listing_id, payment_option),
    CONSTRAINT chk_trade_listing_payment_option CHECK (
        payment_option IN (
            'NATIVE_CHAIN_CURRENCY',
            'STABLECOIN',
            'CBMT',
            'PONTES_TARGET',
            'OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE trade_execution (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id               UUID            NOT NULL REFERENCES trade_listing(id),
    venue_code               VARCHAR(20)     NOT NULL,
    buyer_entity_id          UUID            NOT NULL REFERENCES legal_entity(id),
    seller_entity_id         UUID            NOT NULL REFERENCES legal_entity(id),
    seller_holder_id         UUID            NOT NULL REFERENCES asset_holder(id),
    buyer_holder_id          UUID REFERENCES asset_holder(id),
    asset_id                 UUID            NOT NULL REFERENCES asset(id),
    asset_number             VARCHAR(30)     NOT NULL,
    asset_name               VARCHAR(500)    NOT NULL,
    isin                     VARCHAR(12),
    asset_type               VARCHAR(20)     NOT NULL,
    token_standard           VARCHAR(20)     NOT NULL,
    chain                    VARCHAR(20),
    order_type               VARCHAR(20)     NOT NULL,
    requested_quantity       NUMERIC(38, 18) NOT NULL,
    executed_quantity        NUMERIC(38, 18) NOT NULL,
    unit_price               NUMERIC(38, 18) NOT NULL,
    total_price              NUMERIC(38, 18) NOT NULL,
    payment_option           VARCHAR(30)     NOT NULL,
    settlement_status        VARCHAR(20)     NOT NULL DEFAULT 'SETTLED',
    wallet_preference_mode   VARCHAR(30)     NOT NULL,
    wallet_endpoint_id       UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address           VARCHAR(128)    NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    settled_at               TIMESTAMPTZ,
    CONSTRAINT chk_trade_execution_venue CHECK (
        venue_code IN ('SIMULATED', 'ASSETERA', 'ARCHAX', 'TALOS')
    ),
    CONSTRAINT chk_trade_execution_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_execution_token_standard CHECK (
        token_standard IN ('ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643','SPL')
    ),
    CONSTRAINT chk_trade_execution_chain CHECK (
        chain IS NULL OR chain IN ('ETHEREUM','POLYGON','BASE','SOLANA')
    ),
    CONSTRAINT chk_trade_execution_order_type CHECK (
        order_type IN ('MARKET', 'LIMIT', 'IOC', 'FOK')
    ),
    CONSTRAINT chk_trade_execution_payment_option CHECK (
        payment_option IN (
            'NATIVE_CHAIN_CURRENCY',
            'STABLECOIN',
            'CBMT',
            'PONTES_TARGET',
            'OFFCHAIN_SEPA'
        )
    ),
    CONSTRAINT chk_trade_execution_settlement_status CHECK (
        settlement_status IN ('PENDING', 'SETTLED')
    ),
    CONSTRAINT chk_trade_execution_wallet_preference_mode CHECK (
        wallet_preference_mode IN ('GLOBAL_DEFAULT', 'ASSET_TYPE_DEFAULT', 'ENDPOINT', 'CUSTOM_ADDRESS')
    ),
    CONSTRAINT chk_trade_execution_quantity CHECK (
        requested_quantity > 0
        AND executed_quantity > 0
        AND executed_quantity <= requested_quantity
    ),
    CONSTRAINT chk_trade_execution_price CHECK (
        unit_price > 0
        AND total_price > 0
    )
);

CREATE INDEX idx_trade_execution_buyer          ON trade_execution (buyer_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_seller         ON trade_execution (seller_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_listing        ON trade_execution (listing_id);
CREATE INDEX idx_trade_execution_seller_pending ON trade_execution (seller_holder_id, settlement_status)
    WHERE settlement_status = 'PENDING';
