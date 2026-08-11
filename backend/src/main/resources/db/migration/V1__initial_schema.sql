-- ─────────────────────────────────────────────────────────────────────────────
-- Registerwerk — squashed schema (previously V1–V24, then V1–V9, then V1–V19).
-- Single migration for a clean-slate deploy. Never edit a released version of
-- this file in place — squashing again requires wiping every environment's
-- flyway_schema_history (see backend/CLAUDE.md / the squash note in git history).
--
-- Upgrading an environment that already ran V1–V19: Flyway will fail on this
-- file's changed checksum and on the now-absent V2–V19. The schema is unchanged
-- (verified column-, constraint-, index-, comment-, function- and trigger-wise
-- against the pre-squash result), so no DDL needs to run — reconcile the history
-- table instead:
--     DELETE FROM flyway_schema_history WHERE version <> '1';
--     UPDATE flyway_schema_history SET checksum = NULL WHERE version = '1';
-- then start with spring.flyway.validate-on-migrate=false for exactly one boot,
-- or run `flyway repair`. A fresh database needs none of this.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Audit sequence (referenced by audit_event DEFAULT) ───────────────────────
CREATE SEQUENCE IF NOT EXISTS audit_event_seq START 1 INCREMENT 1;

-- ═══════════════════════════════════════════════════════════════════════════
-- LEGAL ENTITIES & KYC
-- ═══════════════════════════════════════════════════════════════════════════

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
    -- Retained but never written. Inbound B2B federation is configured tenant-to-tenant in the
    -- Entra portal; Registerwerk never runs an authorization-code flow against a customer's
    -- tenant, so it has no use for their client secret and must not hold one in plaintext.
    idp_client_secret    VARCHAR(500),
    -- Per-legal-entity identity model: whether the operator invites this customer's users as B2B
    -- guests into its own tenant (and therefore manages their MFA), or federates to the
    -- customer's own Entra tenant (and therefore does not). This records operator *intent*;
    -- once a user has actually signed in, app_user.entra_tenant_id is the ground truth.
    identity_model       VARCHAR(20) NOT NULL DEFAULT 'WORKFORCE_GUEST',
    idp_tenant_id        UUID,
    -- Whether MFA performed in the customer's home tenant is trusted here (Entra cross-tenant
    -- access settings). Operator-controlled only: a customer self-asserting "trust my MFA"
    -- would be a privilege-escalation vector.
    idp_mfa_trusted      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_entity_type   CHECK (type IN ('ISSUER','INVESTOR','AUDITOR')),
    CONSTRAINT chk_entity_status CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED')),
    CONSTRAINT chk_kyc_status    CHECK (kyc_status IN ('NOT_STARTED','IN_PROGRESS','APPROVED','REJECTED','EXPIRED')),
    CONSTRAINT chk_legal_entity_identity_model
        CHECK (identity_model IN ('WORKFORCE_MEMBER', 'WORKFORCE_GUEST', 'FEDERATED'))
);

CREATE INDEX idx_legal_entity_type   ON legal_entity (type);
CREATE INDEX idx_legal_entity_status ON legal_entity (status);
CREATE INDEX idx_legal_entity_lei    ON legal_entity (lei_code) WHERE lei_code IS NOT NULL;

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

CREATE TABLE entity_merge_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    merge_type       VARCHAR(20) NOT NULL DEFAULT 'ABSORPTION',
    effective_date   DATE NOT NULL,
    notes            TEXT,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by      UUID,
    CONSTRAINT chk_merge_type    CHECK (merge_type IN ('ABSORPTION','CONSOLIDATION')),
    CONSTRAINT chk_no_self_merge CHECK (source_entity_id != target_entity_id)
);

CREATE INDEX idx_merge_source ON entity_merge_record (source_entity_id);
CREATE INDEX idx_merge_target ON entity_merge_record (target_entity_id);

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
    ON onboarding_token (legal_entity_id) WHERE used_at IS NULL;
CREATE INDEX idx_onboarding_token_hash ON onboarding_token (token_hash);

-- ═══════════════════════════════════════════════════════════════════════════
-- APP USERS
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE app_user (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(320) NOT NULL UNIQUE,
    password_hash    VARCHAR(100),
    full_name        VARCHAR(200),
    role             VARCHAR(30)  NOT NULL DEFAULT 'REGISTRY_ADMIN',
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    legal_entity_id  UUID REFERENCES legal_entity(id),
    auth_provider    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at    TIMESTAMPTZ,
    created_by       UUID,
    -- TOTP step-up MFA (RFC 6238)
    totp_secret      TEXT,
    totp_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_enrolled_at TIMESTAMPTZ,
    -- Microsoft Entra ID identity mapping. entra_object_id is the token's `oid`: stable per user
    -- per tenant, and the join key between an Entra principal and this row. Without it app_user.id
    -- and the token's `sub` would be unrelated values, so SecurityUtils.extractUserId() would
    -- return an id matching no row — breaking step-up issuance, dual-control self-approval checks
    -- and every audit_event actor_id. NULL for LOCAL accounts.
    entra_object_id  UUID,
    -- Home tenant of the principal (token `tid`). When it differs from our own tenant the user is
    -- federated from a customer's tenant, and we can neither read nor manage their MFA methods.
    entra_tenant_id  UUID,
    -- Advisory cache of the Microsoft Graph second-factor lookup, so the nav banner does not cost
    -- a Graph round-trip on every page load. NEVER an authorisation input: Conditional Access is
    -- the enforcement point, and a stale cache must not be able to grant or deny access.
    entra_mfa_registered_at TIMESTAMPTZ,
    entra_mfa_checked_at    TIMESTAMPTZ,
    CONSTRAINT chk_app_user_role CHECK (
        role IN (
            'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER',
            'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER'
        )
    ),
    CONSTRAINT chk_app_user_auth_provider CHECK (auth_provider IN ('LOCAL','ENTRA'))
);

CREATE INDEX idx_app_user_email_lower     ON app_user (LOWER(email));
CREATE INDEX idx_app_user_legal_entity_id ON app_user (legal_entity_id);

COMMENT ON COLUMN app_user.entra_object_id IS
    'Entra object id (token `oid`). Stable per user per tenant; the join key between an Entra '
    'principal and this row. NULL for LOCAL accounts.';

-- Partial unique index rather than a UNIQUE constraint: every LOCAL account leaves this NULL,
-- and Postgres treats NULLs as distinct, but a partial index states the intent explicitly and
-- keeps the index off the many LOCAL rows.
CREATE UNIQUE INDEX ux_app_user_entra_object_id
    ON app_user (entra_object_id)
    WHERE entra_object_id IS NOT NULL;

CREATE TABLE app_user_role (
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role        VARCHAR(30) NOT NULL,
    PRIMARY KEY (app_user_id, role),
    CONSTRAINT chk_app_user_role_entry CHECK (
        role IN (
            'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER',
            'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER'
        )
    )
);

CREATE TABLE app_user_action_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(128) NOT NULL,
    token_type  VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_by  UUID,
    CONSTRAINT chk_app_user_action_token_type CHECK (
        token_type IN ('REGISTRATION','PASSWORD_RESET')
    )
);

CREATE UNIQUE INDEX idx_app_user_action_token_hash   ON app_user_action_token (token_hash);
CREATE INDEX        idx_app_user_action_token_active
    ON app_user_action_token (app_user_id, token_type) WHERE consumed_at IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN REGISTRY
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE chain_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier          VARCHAR(50)  NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    chain_type          VARCHAR(10)  NOT NULL,
    network_type        VARCHAR(10)  NOT NULL,
    chain_id            BIGINT,
    rpc_url             VARCHAR(500) NOT NULL,
    ws_url              VARCHAR(500),
    fallback_rpc_urls   TEXT,
    block_explorer_url  VARCHAR(200),
    graph_node_url      VARCHAR(300),
    graph_subgraph_name VARCHAR(100),
    enabled             BOOLEAN      NOT NULL DEFAULT true,
    -- Canton-specific
    application_id      VARCHAR(255),
    synchronizer_id     VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_chain_type   CHECK (chain_type   IN ('EVM','SOLANA','STARKNET','STELLAR','CANTON')),
    CONSTRAINT chk_network_type CHECK (network_type IN ('MAINNET','TESTNET'))
);

CREATE INDEX idx_chain_config_type    ON chain_config (chain_type, network_type);
CREATE INDEX idx_chain_config_enabled ON chain_config (enabled);

CREATE TABLE rpc_node (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chain_config_id        UUID         NOT NULL REFERENCES chain_config(id) ON DELETE CASCADE,
    url                    VARCHAR(512) NOT NULL,
    label                  VARCHAR(100),
    enabled                BOOLEAN      NOT NULL DEFAULT true,
    exclusive              BOOLEAN      NOT NULL DEFAULT false,
    latest_block_number    BIGINT,
    block_last_advanced_at TIMESTAMPTZ,
    last_checked_at        TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    healthy                BOOLEAN      NOT NULL DEFAULT false,
    consecutive_failures   INT          NOT NULL DEFAULT 0,
    lag_from_best          INT,
    syncing                BOOLEAN      NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rpc_node_chain  ON rpc_node (chain_config_id);
CREATE INDEX idx_rpc_node_usable ON rpc_node (chain_config_id, enabled, healthy);

-- ═══════════════════════════════════════════════════════════════════════════
-- ASSETS
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE asset (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_number     VARCHAR(30) NOT NULL UNIQUE,
    issuer_id        UUID NOT NULL REFERENCES legal_entity(id),
    name             VARCHAR(500) NOT NULL,
    isin             VARCHAR(12),
    token_standard   VARCHAR(30) NOT NULL,
    onchain_level    VARCHAR(10) NOT NULL DEFAULT 'NONE',
    jurisdiction     VARCHAR(20),
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    termsheet_doc_id UUID REFERENCES kyc_document(id),
    public_data      JSONB,
    -- Preferred deployment target
    chain            VARCHAR(20),
    network          VARCHAR(10),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_holder_sync_time TIMESTAMP WITH TIME ZONE,
    -- Optimistic-lock version. AssetLifecycleService's submit/approve/reject/issue/suspend/
    -- reactivate/redeem methods all do a read-modify-write on asset.status; without a version
    -- check two concurrent transitions can both pass their precondition and both commit
    -- silently. JPA's @Version turns a lost update into an optimistic-lock failure (HTTP 409) —
    -- mirrors the identical guard on asset_holder.version below.
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            -- securities-grade additions
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_onchain_level CHECK (onchain_level IN ('NONE','SIMPLE','CONTROL')),
    CONSTRAINT chk_asset_status  CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED')
    ),
    CONSTRAINT chk_asset_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_asset_network CHECK (network IS NULL OR network IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_asset_chain_network_pair CHECK (
        (chain IS NULL AND network IS NULL) OR (chain IS NOT NULL AND network IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_asset_isin          ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX        idx_asset_isin_btree    ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX        idx_asset_issuer        ON asset (issuer_id);
CREATE INDEX        idx_asset_status        ON asset (status);
CREATE INDEX        idx_asset_public_data   ON asset USING GIN (public_data) WHERE public_data IS NOT NULL;
CREATE INDEX        idx_asset_last_holder_sync_time ON asset(last_holder_sync_time DESC NULLS LAST);
CREATE INDEX        idx_asset_chain_network ON asset (chain, network) WHERE chain IS NOT NULL;

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
    ON asset_document(asset_id, document_type) WHERE deleted_at IS NULL;

CREATE TABLE asset_deployment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           UUID NOT NULL REFERENCES asset(id),
    chain              VARCHAR(20) NOT NULL,
    network            VARCHAR(10) NOT NULL,
    contract_address   VARCHAR(66),
    deployed_at        TIMESTAMPTZ,
    deployed_by_tx     VARCHAR(66),
    deployment_status  VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT chk_chain CHECK (
        chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_network    CHECK (network IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_dep_status CHECK (deployment_status IN ('PENDING','CONFIRMED','FAILED'))
);

CREATE UNIQUE INDEX idx_deployment_address
    ON asset_deployment (chain, network, contract_address) WHERE contract_address IS NOT NULL;
CREATE INDEX idx_deployment_asset ON asset_deployment (asset_id);

CREATE TABLE asset_holder (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id          UUID NOT NULL REFERENCES asset(id),
    investor_id       UUID NOT NULL REFERENCES legal_entity(id),
    -- 0x-prefixed (EVM/Starknet) addresses are normalized to lowercase at write time
    -- (EvmUtils.normalizeAddress), matching indexer.api.HolderDataService's convention, so that
    -- an indexer-persisted address and a UI-entered checksummed one match. Solana (base58) and
    -- Stellar (base32) addresses also live in this column and are case-SENSITIVE by
    -- construction — lowercasing those would corrupt them, so normalization is 0x-only.
    wallet_address    VARCHAR(66) NOT NULL,
    whitelisted       BOOLEAN NOT NULL DEFAULT false,
    whitelist_tx_hash VARCHAR(66),
    nominal_amount    NUMERIC(38,18) NOT NULL DEFAULT 0,
    acquisition_date  DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_holder_wallet   ON asset_holder (asset_id, wallet_address);
CREATE INDEX        idx_holder_investor ON asset_holder (investor_id);
CREATE INDEX        idx_holder_asset    ON asset_holder (asset_id);

CREATE TABLE mint_control_rule (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    target_address       VARCHAR(66) NOT NULL,
    rule_type            VARCHAR(30) NOT NULL,
    max_amount           NUMERIC(38,18),
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

-- Bond terms
CREATE TABLE asset_bond_terms (
    asset_id          UUID PRIMARY KEY REFERENCES asset(id),
    face_value        NUMERIC(38,18) NOT NULL,
    currency_iso      VARCHAR(3) NOT NULL,
    issue_date        DATE NOT NULL,
    maturity_date     DATE NOT NULL,
    coupon_rate       NUMERIC(10,8),
    reference_rate    VARCHAR(32),
    spread            NUMERIC(10,8),
    day_count         VARCHAR(20) NOT NULL,
    payment_frequency VARCHAR(16) NOT NULL,
    callable          BOOLEAN NOT NULL DEFAULT FALSE,
    call_schedule     JSONB,
    bond_status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- Fraction of face value paid at issue (e.g. 0.80 for 80%). The entire point of a
    -- zero-coupon bond; fixed/floating bonds leave it at par.
    issue_price       NUMERIC(10, 8) NOT NULL DEFAULT 1.0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Vault state (ERC-4626 / ERC-7540)
CREATE TABLE asset_vault_state (
    asset_id               UUID PRIMARY KEY REFERENCES asset(id),
    underlying_asset_id    UUID REFERENCES asset(id),
    deposit_cap            NUMERIC(78,0),
    min_settlement_delay   INTEGER,
    latest_nav_per_share   NUMERIC(38,18),
    latest_nav_strike_at   TIMESTAMPTZ,
    latest_nav_report_hash BYTEA,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE vault_nav_strike (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      UUID NOT NULL REFERENCES asset(id),
    strike_id     BIGINT NOT NULL,
    nav_per_share NUMERIC(38,18) NOT NULL,
    effective_at  TIMESTAMPTZ NOT NULL,
    report_hash   BYTEA,
    report_doc_id UUID,
    struck_by     UUID NOT NULL,
    struck_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tx_hash       VARCHAR(80),
    UNIQUE (asset_id, strike_id)
);

CREATE TABLE vault_request (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id       UUID NOT NULL REFERENCES asset(id),
    request_id     NUMERIC(78,0) NOT NULL,
    request_type   VARCHAR(8) NOT NULL,
    controller_addr VARCHAR(80) NOT NULL,
    owner_addr     VARCHAR(80) NOT NULL,
    asset_amount   NUMERIC(78,0),
    share_amount   NUMERIC(78,0),
    request_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fulfilled_at   TIMESTAMPTZ,
    fulfilled_tx   VARCHAR(80),
    nav_at_fulfill NUMERIC(38,18),
    UNIQUE (asset_id, request_id)
);

-- ERC-3525 / Starknet SFT slots
CREATE TABLE asset_slot (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id   UUID NOT NULL REFERENCES asset(id),
    slot_id    NUMERIC(78,0) NOT NULL,
    name       VARCHAR(200),
    metadata   JSONB,
    supply_cap NUMERIC(78,0),
    paused     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, slot_id)
);

CREATE TABLE asset_token_unit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      UUID NOT NULL REFERENCES asset(id),
    slot_id       NUMERIC(78,0) NOT NULL,
    token_id      NUMERIC(78,0) NOT NULL,
    owner_addr    VARCHAR(80),
    token_value   NUMERIC(78,0) NOT NULL DEFAULT 0,
    frozen        BOOLEAN NOT NULL DEFAULT FALSE,
    freeze_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, token_id)
);

CREATE TABLE asset_coupon_payment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID NOT NULL REFERENCES asset(id),
    slot_id         NUMERIC(78,0),
    period_no       INTEGER NOT NULL,
    scheduled_date  DATE NOT NULL,
    paid_date       DATE,
    amount_per_unit NUMERIC(38,18),
    coupon_status   VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    tx_ref          VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ON vault_request (asset_id, request_status);
CREATE INDEX ON asset_slot (asset_id);
CREATE INDEX ON asset_token_unit (asset_id, slot_id);
CREATE INDEX ON asset_coupon_payment (asset_id, coupon_status);
CREATE INDEX ON vault_nav_strike (asset_id, effective_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- TOKEN TRANSFERS & INDEXING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE token_transfer (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID        REFERENCES asset(id),
    deployment_id    UUID        REFERENCES asset_deployment(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    -- Widened to 128 (from an original 66) and from/to/block_number made nullable: Solana
    -- signatures are base58-encoded ~87-88 chars (already wider than 66), and
    -- SolanaTransferSyncService leaves from/to NULL for mints/burns and never sets block_number
    -- (Solana has slots, not blocks) — the original NOT NULL/VARCHAR(66) constraints made every
    -- such insert fail. Also gives Starknet felt addresses and Stellar G-addresses headroom.
    contract_address VARCHAR(128) NOT NULL,
    from_address     VARCHAR(128),
    to_address       VARCHAR(128),
    token_id         NUMERIC,
    amount           NUMERIC(38,18),
    event_type       VARCHAR(10) NOT NULL,
    tx_hash          VARCHAR(128) NOT NULL,
    block_number     BIGINT,
    log_index        INT,
    slot             BIGINT,
    occurred_at      TIMESTAMPTZ NOT NULL,
    explorer_tx_url  VARCHAR(600),
    raw_data         JSONB,
    CONSTRAINT chk_event_type     CHECK (event_type IN ('MINT','TRANSFER','BURN')),
    CONSTRAINT uq_transfer_evm    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index),
    CONSTRAINT uq_transfer_solana UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot)
);

CREATE INDEX idx_transfer_asset      ON token_transfer (asset_id);
CREATE INDEX idx_transfer_deployment ON token_transfer (deployment_id);
CREATE INDEX idx_transfer_chain      ON token_transfer (chain_config_id, block_number DESC);
CREATE INDEX idx_transfer_contract   ON token_transfer (contract_address, occurred_at DESC);
CREATE INDEX idx_transfer_from       ON token_transfer (from_address);
CREATE INDEX idx_transfer_to         ON token_transfer (to_address);
CREATE INDEX idx_transfer_event_type ON token_transfer (event_type);

CREATE TABLE indexer_state (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID        NOT NULL REFERENCES chain_config(id),
    indexer_type          VARCHAR(30) NOT NULL,
    last_synced_block     BIGINT,
    -- Widened to 200 (from 100) — reused as a generic string cursor (Solana signature, Canton
    -- ledger offset, Horizon paging token), matching what the JPA entity always declared.
    last_synced_signature VARCHAR(200),
    last_synced_at        TIMESTAMPTZ,
    last_error            TEXT,
    consecutive_errors    INT         NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_indexer_state   UNIQUE (chain_config_id, indexer_type),
    CONSTRAINT chk_indexer_type   CHECK (indexer_type IN ('GRAPH_NODE','SOLANA_GEYSER','SOLANA_POLL','CANTON_STREAM','STARKNET_POLL','STELLAR_HORIZON')),
    CONSTRAINT chk_indexer_status CHECK (status IN ('ACTIVE','PAUSED','ERROR'))
);

CREATE INDEX idx_indexer_state_chain  ON indexer_state (chain_config_id);
CREATE INDEX idx_indexer_state_status ON indexer_state (status);

-- Per-mint Solana cursor. indexer_state above is keyed only by (chain, indexer_type), which is
-- too coarse for Solana: one shared cursor across every tracked mint on a chain permanently
-- freezes the "until" boundary and silently loses transfer history once a mint's backlog
-- exceeds MAX_SIGNATURES_PER_POLL. Each mint therefore gets its own advancing cursor.
CREATE TABLE solana_mint_sync_cursor (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    mint_address          VARCHAR(64) NOT NULL,
    last_synced_signature VARCHAR(200),
    last_synced_at        TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_solana_mint_sync_cursor UNIQUE (chain_config_id, mint_address)
);

-- Durable mirror of the Canton Holdings the indexer has seen Created and not yet seen Archived.
-- Daml's Archived ledger event carries only the contract ID, never its former argument payload,
-- so without this table an Archived event cannot be attributed to an instrument/owner/amount.
CREATE TABLE canton_holding_snapshot (
    contract_id      VARCHAR(255) PRIMARY KEY,
    chain_config_id  UUID NOT NULL REFERENCES chain_config(id),
    instrument       VARCHAR(255) NOT NULL,
    owner            VARCHAR(255) NOT NULL,
    amount           NUMERIC(38,18) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_canton_holding_snapshot_chain ON canton_holding_snapshot (chain_config_id);

-- Per-deployment cursor for ConfidentialTravelRuleScreeningService: the last indexed block
-- screened for Travel Rule obligations, so each scheduled run only decrypts and evaluates
-- confidential transfer/mint events new since the previous run.
CREATE TABLE confidential_transfer_screening_state (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    last_screened_block  BIGINT NOT NULL DEFAULT 0,
    last_run_at          TIMESTAMPTZ,
    last_error           TEXT,
    -- Consecutive runs that failed to resolve the earliest unresolved event. The cursor must
    -- not advance unconditionally past a failed decrypt (a transient relayer/KMS hiccup would
    -- permanently and silently skip screening for that transfer), but nor may it retry forever
    -- (a non-transient failure would wedge the service). This bounds the retries; on exhaustion
    -- the service advances past the event and logs at ERROR.
    consecutive_decrypt_failures INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_conf_transfer_screening_deployment UNIQUE (asset_deployment_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- ERC-3643 / ONCHAIN IDENTITY
-- ═══════════════════════════════════════════════════════════════════════════

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

CREATE TABLE erc3643_suite (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id       UUID        NOT NULL UNIQUE REFERENCES asset_deployment(id),
    token_address             VARCHAR(66),
    identity_registry_address VARCHAR(66),
    identity_registry_storage VARCHAR(66),
    compliance_address        VARCHAR(66),
    claim_topics_registry     VARCHAR(66),
    trusted_issuers_registry  VARCHAR(66),
    factory_tx_hash           VARCHAR(66),
    is_confidential           BOOLEAN     NOT NULL DEFAULT false,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE erc3643_compliance_module (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id          UUID        NOT NULL REFERENCES erc3643_suite(id),
    module_address    VARCHAR(66) NOT NULL,
    module_type       VARCHAR(50) NOT NULL,
    parameters        JSONB,
    max_investors     INTEGER,
    max_balance       NUMERIC(38,0),
    transfer_cooldown INTEGER,
    blocked_countries SMALLINT[],
    added_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at        TIMESTAMPTZ
);

CREATE TABLE erc3643_trusted_issuer (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id        UUID        NOT NULL REFERENCES erc3643_suite(id),
    issuer_address  VARCHAR(66) NOT NULL,
    claim_topics    BIGINT[]    NOT NULL DEFAULT '{}',
    legal_entity_id UUID        REFERENCES legal_entity(id),
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at      TIMESTAMPTZ
);

CREATE TABLE erc3643_claim_topic (
    id       UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id UUID   NOT NULL REFERENCES erc3643_suite(id),
    topic    BIGINT NOT NULL,
    label    VARCHAR(50),
    UNIQUE (suite_id, topic)
);

CREATE TABLE erc3643_identity_registry (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id            UUID        NOT NULL REFERENCES erc3643_suite(id),
    wallet_address      VARCHAR(66) NOT NULL,
    onchain_identity_id UUID        NOT NULL REFERENCES onchain_identity(id),
    country_code        SMALLINT,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by_tx    VARCHAR(66),
    removed_at          TIMESTAMPTZ
);

-- Partial, not a plain UNIQUE (suite_id, wallet_address): removal here is a soft-delete and
-- registerInvestor always INSERTs a new row rather than reactivating, so an unconditional
-- constraint made re-registering a previously-removed wallet fail with an unhandled 500.
CREATE UNIQUE INDEX erc3643_identity_registry_active_unique
    ON erc3643_identity_registry (suite_id, wallet_address)
    WHERE removed_at IS NULL;

CREATE INDEX idx_erc3643_ir_suite    ON erc3643_identity_registry (suite_id);
CREATE INDEX idx_erc3643_ir_identity ON erc3643_identity_registry (onchain_identity_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- BLOCKCHAIN TRANSACTIONS & WALLETS
-- ═══════════════════════════════════════════════════════════════════════════

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

CREATE TABLE operator_wallet (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(120) NOT NULL UNIQUE,
    type          VARCHAR(10)  NOT NULL,
    address       VARCHAR(64)  NOT NULL,
    keystore_path VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID,
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
    CONSTRAINT chk_risk_level CHECK (risk_level IS NULL OR risk_level IN ('LOW','MEDIUM','HIGH'))
);

CREATE UNIQUE INDEX idx_endpoint_entity_address
    ON address_endpoint (owner_type, owner_id, address) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX idx_endpoint_operator_address
    ON address_endpoint (owner_type, address) WHERE owner_id IS NULL;
CREATE INDEX idx_endpoint_owner   ON address_endpoint (owner_type, owner_id);
CREATE INDEX idx_endpoint_address ON address_endpoint (address);

-- ═══════════════════════════════════════════════════════════════════════════
-- TRADING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE company_trader_settings (
    legal_entity_id              UUID PRIMARY KEY REFERENCES legal_entity(id) ON DELETE CASCADE,
    default_payment_option       VARCHAR(30) NOT NULL DEFAULT 'OFFCHAIN_SEPA',
    immediate_settlement_enabled BOOLEAN     NOT NULL DEFAULT true,
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                   UUID,
    CONSTRAINT chk_trader_default_payment_option CHECK (
        default_payment_option IN (
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE company_trader_wallet_default (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID        NOT NULL REFERENCES legal_entity(id) ON DELETE CASCADE,
    asset_type      VARCHAR(20),
    target_type     VARCHAR(20) NOT NULL,
    endpoint_id     UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address  VARCHAR(128),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_trader_wallet_asset_type CHECK (
        asset_type IS NULL OR asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trader_wallet_target_type CHECK (target_type IN ('ENDPOINT','CUSTOM_ADDRESS')),
    CONSTRAINT chk_trader_wallet_target_payload CHECK (
        (target_type = 'ENDPOINT' AND endpoint_id IS NOT NULL AND wallet_address IS NULL)
        OR
        (target_type = 'CUSTOM_ADDRESS' AND endpoint_id IS NULL AND wallet_address IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_trader_wallet_default_global
    ON company_trader_wallet_default (legal_entity_id) WHERE asset_type IS NULL;
CREATE UNIQUE INDEX idx_trader_wallet_default_asset_type
    ON company_trader_wallet_default (legal_entity_id, asset_type) WHERE asset_type IS NOT NULL;
CREATE INDEX idx_trader_wallet_default_entity ON company_trader_wallet_default (legal_entity_id);

CREATE TABLE trade_listing (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_code         VARCHAR(20)    NOT NULL,
    seller_entity_id   UUID           NOT NULL REFERENCES legal_entity(id),
    seller_holder_id   UUID           NOT NULL REFERENCES asset_holder(id),
    asset_id           UUID           NOT NULL REFERENCES asset(id),
    asset_number       VARCHAR(30)    NOT NULL,
    asset_name         VARCHAR(500)   NOT NULL,
    isin               VARCHAR(12),
    asset_type         VARCHAR(20)    NOT NULL,
    token_standard     VARCHAR(20)    NOT NULL,
    chain              VARCHAR(20),
    status             VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    quantity_total     NUMERIC(38,18) NOT NULL,
    quantity_available NUMERIC(38,18) NOT NULL,
    price_per_unit     NUMERIC(38,18) NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_trade_listing_venue CHECK (
        venue_code IN ('SIMULATED','ASSETERA','ARCHAX','TALOS')
    ),
    CONSTRAINT chk_trade_listing_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_listing_status CHECK (
        status IN ('OPEN','PARTIALLY_FILLED','FILLED','CANCELLED')
    ),
    CONSTRAINT chk_trade_listing_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_trade_listing_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_trade_listing_quantity CHECK (
        quantity_total > 0 AND quantity_available >= 0 AND quantity_available <= quantity_total
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
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE trade_execution (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id             UUID           NOT NULL REFERENCES trade_listing(id),
    venue_code             VARCHAR(20)    NOT NULL,
    buyer_entity_id        UUID           NOT NULL REFERENCES legal_entity(id),
    seller_entity_id       UUID           NOT NULL REFERENCES legal_entity(id),
    seller_holder_id       UUID           NOT NULL REFERENCES asset_holder(id),
    buyer_holder_id        UUID           REFERENCES asset_holder(id),
    asset_id               UUID           NOT NULL REFERENCES asset(id),
    asset_number           VARCHAR(30)    NOT NULL,
    asset_name             VARCHAR(500)   NOT NULL,
    isin                   VARCHAR(12),
    asset_type             VARCHAR(20)    NOT NULL,
    token_standard         VARCHAR(20)    NOT NULL,
    chain                  VARCHAR(20),
    order_type             VARCHAR(20)    NOT NULL,
    requested_quantity     NUMERIC(38,18) NOT NULL,
    executed_quantity      NUMERIC(38,18) NOT NULL,
    unit_price             NUMERIC(38,18) NOT NULL,
    total_price            NUMERIC(38,18) NOT NULL,
    payment_option         VARCHAR(30)    NOT NULL,
    settlement_status      VARCHAR(20)    NOT NULL DEFAULT 'SETTLED',
    wallet_preference_mode VARCHAR(30)    NOT NULL,
    wallet_endpoint_id     UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address         VARCHAR(128)   NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    settled_at             TIMESTAMPTZ,
    -- Populated for FAILED/CANCELLED/REFUNDED executions — a venue rejection previously threw
    -- and rolled back the whole transaction, leaving no record of the attempt at all.
    failure_reason         TEXT,
    -- Evidence of the actual cash leg (a stablecoin tx hash, a SEPA transfer reference, …).
    -- Settling a PENDING trade otherwise required nothing beyond the buyer's own HTTP call,
    -- leaving reconciliation with pure self-attestation to check.
    payment_reference      VARCHAR(255),
    CONSTRAINT chk_trade_execution_venue CHECK (
        venue_code IN ('SIMULATED','ASSETERA','ARCHAX','TALOS')
    ),
    CONSTRAINT chk_trade_execution_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_execution_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_trade_execution_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_trade_execution_order_type CHECK (
        order_type IN ('MARKET','LIMIT','IOC','FOK')
    ),
    CONSTRAINT chk_trade_execution_payment_option CHECK (
        payment_option IN (
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    ),
    CONSTRAINT chk_trade_execution_settlement_status CHECK (
        settlement_status IN ('PENDING','SETTLED','FAILED','CANCELLED','REFUNDED')
    ),
    CONSTRAINT chk_trade_execution_wallet_preference_mode CHECK (
        wallet_preference_mode IN (
            'GLOBAL_DEFAULT','ASSET_TYPE_DEFAULT','ENDPOINT','CUSTOM_ADDRESS'
        )
    ),
    CONSTRAINT chk_trade_execution_quantity CHECK (
        requested_quantity > 0 AND executed_quantity > 0 AND executed_quantity <= requested_quantity
    ),
    CONSTRAINT chk_trade_execution_price CHECK (unit_price > 0 AND total_price > 0)
);

CREATE INDEX idx_trade_execution_buyer          ON trade_execution (buyer_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_seller         ON trade_execution (seller_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_listing        ON trade_execution (listing_id);
CREATE INDEX idx_trade_execution_seller_pending ON trade_execution (seller_holder_id, settlement_status)
    WHERE settlement_status = 'PENDING';
CREATE INDEX idx_trade_execution_pending_created ON trade_execution (created_at)
    WHERE settlement_status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════════════
-- EXTERNAL REFERENCES
-- ═══════════════════════════════════════════════════════════════════════════

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
    CONSTRAINT chk_company_external_reference_subject_type CHECK (
        subject_type IN (
            'LEGAL_ENTITY','ASSET','ASSET_HOLDER','ERC3643_IDENTITY_REGISTRY_ENTRY'
        )
    )
);

CREATE INDEX idx_company_external_reference_owner
    ON company_external_reference (owner_legal_entity_id);
CREATE INDEX idx_company_external_reference_lookup
    ON company_external_reference (owner_legal_entity_id, external_id);
CREATE INDEX idx_company_external_reference_owner_type
    ON company_external_reference (owner_legal_entity_id, subject_type, updated_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- COMPLIANCE: §16 SPERRVERMERK / NATURAL PERSONS / BENEFICIAL OWNERS
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE holder_block (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                UUID,
    asset_id                 UUID,
    wallet_address           TEXT NOT NULL,
    block_type               VARCHAR(30) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    legal_basis              TEXT NOT NULL,
    court_ref                TEXT,
    document_id              UUID,
    starts_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at               TIMESTAMPTZ,
    lifted_at                TIMESTAMPTZ,
    lifted_by                UUID,
    lift_reason              TEXT,
    on_chain_freeze_tx_hash  TEXT,
    created_by               UUID NOT NULL,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_holder_block_wallet  ON holder_block (wallet_address) WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_asset   ON holder_block (asset_id)       WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_entity  ON holder_block (entity_id)      WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_expires ON holder_block (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

CREATE TABLE natural_person (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    given_name            TEXT NOT NULL,
    family_name           TEXT NOT NULL,
    date_of_birth         DATE,
    nationality           VARCHAR(2),
    country_of_residence  VARCHAR(2),
    tax_id                TEXT,
    tax_id_country        VARCHAR(2),
    address_line1         TEXT,
    address_line2         TEXT,
    city                  TEXT,
    postal_code           TEXT,
    country               VARCHAR(2),
    pep_status            VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    pep_status_updated_at TIMESTAMPTZ,
    redacted              BOOLEAN NOT NULL DEFAULT FALSE,
    redacted_at           TIMESTAMPTZ,
    redacted_by           UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE beneficial_owner (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID NOT NULL,
    natural_person_id UUID NOT NULL,
    ownership_pct     NUMERIC(5,2),
    control_type      VARCHAR(30) NOT NULL,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ceased_at         TIMESTAMPTZ,
    source            TEXT,
    verified_by       UUID,
    verified_at       TIMESTAMPTZ,
    notes             TEXT,
    UNIQUE (entity_id, natural_person_id, control_type, registered_at)
);

CREATE INDEX idx_beneficial_owner_entity ON beneficial_owner (entity_id) WHERE ceased_at IS NULL;
CREATE INDEX idx_beneficial_owner_person ON beneficial_owner (natural_person_id);

CREATE TABLE holder_identity (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_holder_id   UUID NOT NULL UNIQUE,
    legal_entity_id   UUID,
    natural_person_id UUID,
    CONSTRAINT chk_holder_identity_exactly_one CHECK (
        (legal_entity_id IS NOT NULL)::INT + (natural_person_id IS NOT NULL)::INT = 1
    )
);

CREATE INDEX idx_holder_identity_entity ON holder_identity (legal_entity_id);
CREATE INDEX idx_holder_identity_person ON holder_identity (natural_person_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- SANCTIONS SCREENING (GwG §10, AMLD6, MiCAR Art. 60)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE screening_run (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID,
    natural_person_id UUID,
    trigger_type      VARCHAR(30) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider          TEXT NOT NULL,
    lists_checked     TEXT[],
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    error_message     TEXT,
    initiated_by      UUID
);

CREATE TABLE screening_hit (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                   UUID NOT NULL REFERENCES screening_run(id),
    list_source              TEXT NOT NULL,
    category                 VARCHAR(20) NOT NULL DEFAULT 'SANCTIONS',
    matched_field            TEXT NOT NULL,
    matched_value            TEXT NOT NULL,
    match_score              NUMERIC(5,2),
    match_details            JSONB,
    accepted                 BOOLEAN,
    accepted_by              UUID,
    accepted_at              TIMESTAMPTZ,
    accept_reason            TEXT,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_screening_run_entity  ON screening_run (entity_id)        WHERE entity_id IS NOT NULL;
CREATE INDEX idx_screening_run_person  ON screening_run (natural_person_id) WHERE natural_person_id IS NOT NULL;
CREATE INDEX idx_screening_run_status  ON screening_run (status)            WHERE status IN ('PENDING','HIT');
CREATE INDEX idx_screening_hit_run     ON screening_hit (run_id);
CREATE INDEX idx_screening_hit_pending ON screening_hit (run_id)            WHERE accepted IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- TRAVEL RULE / TFR (Reg EU 2023/1113)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE travel_rule_message (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction            VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING_SEND',
    token_transfer_id    UUID,
    asset_id             UUID,
    originator_vasp_did  TEXT,
    beneficiary_vasp_did TEXT,
    originator_wallet    TEXT NOT NULL,
    beneficiary_wallet   TEXT NOT NULL,
    amount               NUMERIC(38,18),
    currency_symbol      TEXT,
    ivms101_payload      JSONB,
    protocol             TEXT,
    protocol_message_id  TEXT,
    sent_at              TIMESTAMPTZ,
    acknowledged_at      TIMESTAMPTZ,
    received_at          TIMESTAMPTZ,
    verified_at          TIMESTAMPTZ,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trm_direction_status   ON travel_rule_message (direction, status);
CREATE INDEX idx_trm_transfer           ON travel_rule_message (token_transfer_id) WHERE token_transfer_id IS NOT NULL;
CREATE INDEX idx_trm_originator_wallet  ON travel_rule_message (originator_wallet);
CREATE INDEX idx_trm_beneficiary_wallet ON travel_rule_message (beneficiary_wallet);

-- ═══════════════════════════════════════════════════════════════════════════
-- CORPORATE ACTIONS
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE corporate_action (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                 UUID NOT NULL,
    action_type              VARCHAR(30) NOT NULL,
    status                   VARCHAR(30) NOT NULL DEFAULT 'ANNOUNCED',
    announcement_date        DATE,
    record_date              DATE,
    ex_date                  DATE,
    payment_date             DATE,
    ratio_numerator          NUMERIC(38,18),
    ratio_denominator        NUMERIC(38,18),
    amount_per_unit          NUMERIC(38,18),
    total_amount             NUMERIC(38,18),
    currency                 VARCHAR(3),
    coupon_payment_id        UUID,
    bond_period_start        DATE,
    bond_period_end          DATE,
    settlement_tx_hash       TEXT,
    settlement_chain         TEXT,
    settled_at               TIMESTAMPTZ,
    initiated_by             UUID NOT NULL,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    notes                    TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE corporate_action_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    corporate_action_id UUID NOT NULL REFERENCES corporate_action(id),
    asset_holder_id     UUID NOT NULL,
    -- Denormalized from AssetHolder.investorId at snapshot time — lets the Steuerbescheinigung
    -- query "this investor's total income for tax year N" without a cross-module join.
    investor_id         UUID,
    wallet_address      TEXT NOT NULL,
    nominal_at_record   NUMERIC(38,18) NOT NULL,
    entitlement_amount  NUMERIC(38,18),
    settlement_tx_hash  TEXT,
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ca_asset_status ON corporate_action (asset_id, status);
CREATE INDEX idx_ca_payment_date ON corporate_action (payment_date)
    WHERE status NOT IN ('SETTLED','CLOSED','CANCELLED');
CREATE INDEX idx_ca_entry_action ON corporate_action_entry (corporate_action_id);
CREATE INDEX idx_ca_entry_investor ON corporate_action_entry (investor_id);
CREATE INDEX idx_ca_entry_settled_at ON corporate_action_entry (settled_at) WHERE settled_at IS NOT NULL;
CREATE INDEX idx_ca_entry_holder ON corporate_action_entry (asset_holder_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN DRIFT DETECTION (eWpG §16 — DB is canonical)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE chain_drift_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID NOT NULL,
    deployment_id    UUID NOT NULL,
    chain_config_id  UUID,
    wallet_address   TEXT NOT NULL,
    db_balance       NUMERIC(38,18) NOT NULL,
    onchain_balance  NUMERIC(38,18) NOT NULL,
    delta            NUMERIC(38,18) GENERATED ALWAYS AS (onchain_balance - db_balance) STORED,
    severity         VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    detected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      UUID,
    resolution_notes TEXT,
    ict_incident_id  UUID
);

CREATE INDEX idx_drift_asset_open    ON chain_drift_event (asset_id)   WHERE status = 'OPEN';
CREATE INDEX idx_drift_severity_open ON chain_drift_event (severity)   WHERE status = 'OPEN';
CREATE INDEX idx_drift_detected      ON chain_drift_event (detected_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- REGULATORY REPORTING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE regreport_submission (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type            VARCHAR(30) NOT NULL,
    jurisdiction           VARCHAR(20) NOT NULL,
    -- Transport-only statuses. Regulatory reporting here is an opt-in, non-production draft
    -- generator: none of these states proves official-schema validity, filing, acceptance, or
    -- legal compliance. Earlier authority-outcome labels (ACCEPTED / ACKNOWLEDGED / REJECTED)
    -- described local gateway outcomes far too strongly.
    status                 VARCHAR(30) NOT NULL DEFAULT 'DRAFT_UNVALIDATED',
    reporting_period_start DATE NOT NULL,
    reporting_period_end   DATE NOT NULL,
    entity_id              UUID,
    asset_id               UUID,
    document_s3_key        TEXT,
    document_hash          BYTEA,
    document_signature     BYTEA,
    -- Legacy adapter-evidence columns. Retained so that historical rows keep their evidence;
    -- new code writes transported_at / transport_ref / transport_error instead.
    submitted_at           TIMESTAMPTZ,
    submission_ref         TEXT,
    acknowledged_at        TIMESTAMPTZ,
    rejection_reason       TEXT,
    transported_at         TIMESTAMPTZ,
    transport_ref          TEXT,
    transport_error        TEXT,
    generated_by           UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_regreport_type_period ON regreport_submission (report_type, reporting_period_end);
CREATE INDEX idx_regreport_entity      ON regreport_submission (entity_id) WHERE entity_id IS NOT NULL;
CREATE INDEX idx_regreport_status      ON regreport_submission (status)
    WHERE status IN ('DRAFT_UNVALIDATED', 'NOT_TRANSPORTED', 'TRANSPORT_FAILED',
                     'TRANSPORTED_UNVERIFIED');

-- ═══════════════════════════════════════════════════════════════════════════
-- DORA ICT INCIDENTS & THIRD-PARTY REGISTER (Art. 5-17, Art. 28)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE ict_incident (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category                VARCHAR(30) NOT NULL,
    severity                VARCHAR(20) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'DETECTED',
    title                   TEXT NOT NULL,
    description             TEXT,
    source_event_type       TEXT,
    source_event_ref        UUID,
    detected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- DORA Art. 19(4) / RTS (EU) 2025/301 impose *two* deadlines. Tracking only the 24h
    -- initial-report clock let an incident look "on track" for up to 20h after the stricter
    -- 4h-from-classification deadline had already passed.
    classified_at           TIMESTAMPTZ,
    classification_deadline TIMESTAMPTZ,
    initial_report_deadline TIMESTAMPTZ,
    final_report_deadline   TIMESTAMPTZ,
    initial_reported_at     TIMESTAMPTZ,
    final_reported_at       TIMESTAMPTZ,
    -- Who filed (or decided not to file) the Art. 19 authority notification — arguably the
    -- single most examination-sensitive action in this module, and previously unattributable.
    reported_by             UUID,
    authority_ref           TEXT,
    contained_at            TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    root_cause              TEXT,
    remediation_steps       TEXT,
    assigned_to             UUID,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ict_incident_open     ON ict_incident (status)
    WHERE status NOT IN ('CLOSED','REPORTED_TO_AUTHORITY');
CREATE INDEX idx_ict_incident_deadline ON ict_incident (initial_report_deadline)
    WHERE initial_reported_at IS NULL;
CREATE INDEX idx_ict_incident_severity ON ict_incident (severity, detected_at);

CREATE TABLE third_party_provider (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL,
    category                VARCHAR(30) NOT NULL,
    criticality             VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    lei                     TEXT,
    country                 VARCHAR(2),
    contract_start          DATE,
    contract_end            DATE,
    sub_outsourcing         BOOLEAN NOT NULL DEFAULT FALSE,
    sub_outsourcing_details TEXT,
    primary_contact         TEXT,
    sla_availability_pct    NUMERIC(5,2),
    rto_hours               INTEGER,
    rpo_hours               INTEGER,
    notified_authority      BOOLEAN NOT NULL DEFAULT FALSE,
    notified_at             TIMESTAMPTZ,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════════════════
-- AUDIT LOG — tamper-evident hash chain (eWpRV §6)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE audit_event (
    id           UUID    NOT NULL DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100) NOT NULL,
    subject_type VARCHAR(50)  NOT NULL,
    subject_id   UUID    NOT NULL,
    actor_id     UUID,
    actor_role   VARCHAR(30),
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sequence_no  BIGINT  DEFAULT nextval('audit_event_seq'),
    prev_hash    BYTEA,
    entry_hash   BYTEA,
    entry_sig    BYTEA,
    reverses_event_id UUID,
    correlation_id    UUID
) PARTITION BY RANGE (occurred_at);

COMMENT ON COLUMN audit_event.reverses_event_id IS
    'audit_event.id of the entry this one reverses/corrects, when this row records a '
    'correction (e.g. a compensating force-burn undoing a wrongful mint). No FK by design '
    '(see audit_event: no FK constraints for throughput) — resolved at the application layer.';
COMMENT ON COLUMN audit_event.correlation_id IS
    'Free-form grouping id for audit entries that belong to one logical operation '
    '(e.g. a batch of forced transfers submitted together).';

CREATE INDEX idx_audit_subject    ON audit_event (subject_type, subject_id);
CREATE INDEX idx_audit_actor      ON audit_event (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_event_type ON audit_event (event_type);
CREATE INDEX idx_audit_payload    ON audit_event USING GIN (payload);
CREATE INDEX idx_audit_event_seq  ON audit_event (sequence_no);
CREATE INDEX idx_audit_reverses    ON audit_event (reverses_event_id) WHERE reverses_event_id IS NOT NULL;
CREATE INDEX idx_audit_correlation ON audit_event (correlation_id)    WHERE correlation_id    IS NOT NULL;

-- WORM trigger: UPDATE and DELETE are forbidden even by the table owner (eWpRV §6).
CREATE OR REPLACE FUNCTION audit_event_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable (eWpRV §6). Operation: %', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();

-- Monthly partition auto-creation — also called by AuditPartitionJob.
CREATE OR REPLACE FUNCTION audit_event_ensure_partitions(months_ahead INT DEFAULT 6)
    RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    FOR i IN 0..months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := 'audit_event_' || to_char(month_start, 'YYYY_MM');
        BEGIN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
                part_name, month_start, month_end
            );
        EXCEPTION WHEN duplicate_table THEN
            -- already exists
        END;
    END LOOP;
END;
$$;

-- Default partition catches anything outside the created range
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

-- Bootstrap 12 months of partitions
SELECT audit_event_ensure_partitions(12);

-- Single-row pointer to the most recent audit_event.entry_hash. AuditEventRecorder locks
-- this row with SELECT ... FOR UPDATE for the duration of its append transaction, serializing
-- hash-chain appends across threads and backend instances so the chain can never fork under
-- concurrent/multi-instance load.
CREATE TABLE audit_chain_tip (
    id         BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    entry_hash BYTEA
);
INSERT INTO audit_chain_tip (id, entry_hash) VALUES (TRUE, NULL);

COMMENT ON TABLE audit_chain_tip IS
    'Single-row pointer to the most recent audit_event.entry_hash. AuditEventRecorder '
    'locks this row with SELECT ... FOR UPDATE for the duration of its append transaction, '
    'serializing hash-chain appends across threads and backend instances so the chain '
    'can never fork under concurrent/multi-instance load.';

-- ═══════════════════════════════════════════════════════════════════════════
-- SPRING MODULITH — event publication outbox
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE event_publication (
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INTEGER,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);
CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);

-- ═══════════════════════════════════════════════════════════════════════════
-- QUARTZ PERSISTENT JOB STORE (Quartz 2.3.x PostgreSQL schema)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE qrtz_job_details (
    sched_name        VARCHAR(120) NOT NULL,
    job_name          VARCHAR(200) NOT NULL,
    job_group         VARCHAR(200) NOT NULL,
    description       VARCHAR(250),
    job_class_name    VARCHAR(250) NOT NULL,
    is_durable        BOOLEAN      NOT NULL,
    is_nonconcurrent  BOOLEAN      NOT NULL,
    is_update_data    BOOLEAN      NOT NULL,
    requests_recovery BOOLEAN      NOT NULL,
    job_data          BYTEA,
    PRIMARY KEY (sched_name, job_name, job_group)
);

CREATE TABLE qrtz_triggers (
    sched_name     VARCHAR(120) NOT NULL,
    trigger_name   VARCHAR(200) NOT NULL,
    trigger_group  VARCHAR(200) NOT NULL,
    job_name       VARCHAR(200) NOT NULL,
    job_group      VARCHAR(200) NOT NULL,
    description    VARCHAR(250),
    next_fire_time BIGINT,
    prev_fire_time BIGINT,
    priority       INTEGER,
    trigger_state  VARCHAR(16)  NOT NULL,
    trigger_type   VARCHAR(8)   NOT NULL,
    start_time     BIGINT       NOT NULL,
    end_time       BIGINT,
    calendar_name  VARCHAR(200),
    misfire_instr  SMALLINT,
    job_data       BYTEA,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, job_name, job_group)
        REFERENCES qrtz_job_details (sched_name, job_name, job_group)
);

CREATE TABLE qrtz_simple_triggers (
    sched_name      VARCHAR(120) NOT NULL,
    trigger_name    VARCHAR(200) NOT NULL,
    trigger_group   VARCHAR(200) NOT NULL,
    repeat_count    BIGINT       NOT NULL,
    repeat_interval BIGINT       NOT NULL,
    times_triggered BIGINT       NOT NULL,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES qrtz_triggers (sched_name, trigger_name, trigger_group)
);

CREATE TABLE qrtz_cron_triggers (
    sched_name      VARCHAR(120) NOT NULL,
    trigger_name    VARCHAR(200) NOT NULL,
    trigger_group   VARCHAR(200) NOT NULL,
    cron_expression VARCHAR(200) NOT NULL,
    time_zone_id    VARCHAR(80),
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES qrtz_triggers (sched_name, trigger_name, trigger_group)
);

CREATE TABLE qrtz_fired_triggers (
    sched_name        VARCHAR(120) NOT NULL,
    entry_id          VARCHAR(140) NOT NULL,
    trigger_name      VARCHAR(200) NOT NULL,
    trigger_group     VARCHAR(200) NOT NULL,
    instance_name     VARCHAR(200) NOT NULL,
    fired_time        BIGINT       NOT NULL,
    sched_time        BIGINT       NOT NULL,
    priority          INTEGER      NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    job_name          VARCHAR(200),
    job_group         VARCHAR(200),
    is_nonconcurrent  BOOLEAN,
    requests_recovery BOOLEAN,
    PRIMARY KEY (sched_name, entry_id)
);

CREATE TABLE qrtz_scheduler_state (
    sched_name        VARCHAR(120) NOT NULL,
    instance_name     VARCHAR(200) NOT NULL,
    last_checkin_time BIGINT       NOT NULL,
    checkin_interval  BIGINT       NOT NULL,
    PRIMARY KEY (sched_name, instance_name)
);

CREATE TABLE qrtz_locks (
    sched_name VARCHAR(120) NOT NULL,
    lock_name  VARCHAR(40)  NOT NULL,
    PRIMARY KEY (sched_name, lock_name)
);

CREATE TABLE qrtz_blob_triggers (
    sched_name    VARCHAR(120) NOT NULL,
    trigger_name  VARCHAR(200) NOT NULL,
    trigger_group VARCHAR(200) NOT NULL,
    blob_data     BYTEA,
    PRIMARY KEY (sched_name, trigger_name, trigger_group),
    FOREIGN KEY (sched_name, trigger_name, trigger_group)
        REFERENCES qrtz_triggers (sched_name, trigger_name, trigger_group)
);

CREATE TABLE qrtz_calendars (
    sched_name    VARCHAR(120) NOT NULL,
    calendar_name VARCHAR(200) NOT NULL,
    calendar      BYTEA        NOT NULL,
    PRIMARY KEY (sched_name, calendar_name)
);

CREATE TABLE qrtz_paused_trigger_grps (
    sched_name    VARCHAR(120) NOT NULL,
    trigger_group VARCHAR(200) NOT NULL,
    PRIMARY KEY (sched_name, trigger_group)
);

CREATE INDEX idx_qrtz_j_req_recovery    ON qrtz_job_details    (sched_name, requests_recovery);
CREATE INDEX idx_qrtz_t_next_fire_time  ON qrtz_triggers       (sched_name, next_fire_time);
CREATE INDEX idx_qrtz_t_state           ON qrtz_triggers       (sched_name, trigger_state);
CREATE INDEX idx_qrtz_ft_trig_inst_name ON qrtz_fired_triggers (sched_name, instance_name);

-- ═══════════════════════════════════════════════════════════════════════════
-- DB ROLE SEPARATION — audit log immutability defence-in-depth
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk_app') THEN
        CREATE ROLE registerwerk_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk_audit_reader') THEN
        CREATE ROLE registerwerk_audit_reader NOLOGIN;
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk') THEN
        GRANT registerwerk_app TO registerwerk;
    END IF;
END
$$;

REVOKE ALL ON TABLE audit_event FROM registerwerk_app;
GRANT INSERT ON TABLE audit_event TO registerwerk_app;
GRANT SELECT ON TABLE audit_event TO registerwerk_app;

REVOKE ALL ON TABLE audit_event FROM registerwerk_audit_reader;
GRANT SELECT ON TABLE audit_event TO registerwerk_audit_reader;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO registerwerk_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO registerwerk_app;

-- ═══════════════════════════════════════════════════════════════════════════
-- MiCA CASP AUTHORIZATION REGISTER
-- ═══════════════════════════════════════════════════════════════════════════

-- Counterparty CASP authorization register (MiCA Reg (EU) 2023/1114).
-- The EU-wide transitional period ends on 1 July 2026 (ESMA statement, 17 Apr 2026);
-- from that date, transfers to CASPs without MiCA authorization must not be executed.
-- Records are maintained by compliance officers from the ESMA / NCA registers.

CREATE TABLE casp_authorization (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vasp_did          VARCHAR(255) NOT NULL UNIQUE,
    legal_name        TEXT NOT NULL,
    lei               VARCHAR(20),
    home_member_state VARCHAR(2),
    status            VARCHAR(20) NOT NULL,
    authorization_id  TEXT,
    valid_from        DATE,
    valid_until       DATE,
    source            TEXT,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_casp_authorization_status ON casp_authorization (status);

-- ═══════════════════════════════════════════════════════════════════════════
-- REGISTER STATEMENTS (§19 eWpG) & ENTRY TYPE (§8 eWpG)
-- ═══════════════════════════════════════════════════════════════════════════

-- §19 eWpG obliges the registry operator to provide a CONSUMER holder of a
-- SINGLE-ENTRY (Einzeleintragung) crypto security with a register statement
-- (Registerauszug) in text form: after the initial entry in their favour, after
-- every change to the register content concerning them, and at least once a year.
-- The issued statements are themselves register records and must be retained and
-- auditable, hence a dedicated table rather than fire-and-forget e-mail.

-- ── Asset: entry type (Einzel- vs. Sammeleintragung, §8 eWpG) ─────────────────
ALTER TABLE asset
    ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE';
-- COLLECTIVE = Sammeleintragung (holder is a custodian/Verwahrer),
-- INDIVIDUAL = Einzeleintragung (holder is the investor, pseudonymised),
-- MIXED      = Mischbestand (both forms coexist for the same asset).
COMMENT ON COLUMN asset.entry_type IS
    'eWpG §8 Eintragungsart: COLLECTIVE (Sammel), INDIVIDUAL (Einzel), MIXED.';

-- ── Holder: consumer flag + pseudonymous identifier for single entry ──────────
ALTER TABLE asset_holder
    ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE',
    -- §17(2) eWpG: in a single entry the holder is designated by a unique
    -- pseudonymous identifier rather than by clear name on-chain.
    ADD COLUMN holder_reference VARCHAR(64),
    -- §19(2) eWpG only obliges statements toward CONSUMER holders; institutional
    -- custodians in a collective entry are excluded.
    ADD COLUMN is_consumer BOOLEAN NOT NULL DEFAULT false,
    -- §17(2) eWpG additional single-entry register content:
    ADD COLUMN third_party_rights TEXT,
    ADD COLUMN disposal_restrictions TEXT,
    ADD COLUMN legal_capacity_note TEXT,
    -- Tracks when the last §19 annual statement was issued, to drive the scheduler.
    ADD COLUMN last_statement_at TIMESTAMPTZ,
    -- Optimistic-lock version: nominal_amount is the legally canonical register
    -- balance (eWpG §16) and is mutated by read-modify-write in several paths that
    -- can run concurrently (trade settlement, manual §24 corrections, indexer sync).
    -- JPA's @Version turns a lost update into an optimistic-lock failure (HTTP 409).
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    -- Soft-delete marker. Removal must never hard-delete the row: a §16 eWpG register entry
    -- disappearing entirely conflicts with retention/tamper-evidence obligations.
    ADD COLUMN removed_at TIMESTAMPTZ;

COMMENT ON COLUMN asset_holder.removed_at IS
    'Soft-delete marker. NULL = still an active register entry. Non-null = the instant '
    'HolderService.removeHolder closed this holder out; the row is retained (never hard-deleted) '
    'for eWpG §16 retention/tamper-evidence. Compliance-facing reads should exclude removed rows '
    '(see AssetHolderRepository.findActive* methods); reconciliation/audit reads may still need them.';

-- Compliance-facing listings filter on this predicate constantly (findActiveByAssetId /
-- findActiveByInvestorId / existsActiveBy...); index it alongside the existing lookup columns.
CREATE INDEX idx_holder_asset_active    ON asset_holder (asset_id)    WHERE removed_at IS NULL;
CREATE INDEX idx_holder_investor_active ON asset_holder (investor_id) WHERE removed_at IS NULL;

COMMENT ON COLUMN asset_holder.holder_reference IS
    'eWpG §17(2) pseudonymous unique identifier for single-entry (Einzeleintragung) holders.';

CREATE UNIQUE INDEX idx_asset_holder_reference
    ON asset_holder (asset_id, holder_reference)
    WHERE holder_reference IS NOT NULL;

-- ── Register statement records (§19 eWpG) ─────────────────────────────────────
CREATE TABLE register_statement (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_id        UUID NOT NULL REFERENCES asset_holder(id),
    asset_id         UUID NOT NULL REFERENCES asset(id),
    investor_id      UUID NOT NULL REFERENCES legal_entity(id),
    -- Trigger: INITIAL_ENTRY, CHANGE, ANNUAL, ON_DEMAND (§19(1)).
    trigger          VARCHAR(20) NOT NULL,
    -- Snapshot of register content at issuance time (the statement is a record).
    nominal_amount   NUMERIC(38,18) NOT NULL,
    wallet_address   VARCHAR(66),
    holder_reference VARCHAR(64),
    content_hash     VARCHAR(66) NOT NULL,    -- keccak/sha-256 of the rendered PDF
    pdf_document_id  UUID,                    -- reference into the document store
    -- Delivery status of the text-form statement (§19: "in Textform").
    delivery_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivery_channel VARCHAR(20),
    delivery_error   TEXT,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX idx_register_statement_holder ON register_statement (holder_id);
CREATE INDEX idx_register_statement_investor ON register_statement (investor_id);
CREATE INDEX idx_register_statement_issued ON register_statement (issued_at);
CREATE INDEX idx_register_statement_delivery ON register_statement (delivery_status)
    WHERE delivery_status IN ('PENDING', 'FAILED');

-- ═══════════════════════════════════════════════════════════════════════════
-- REGISTER INSPECTION (§10 eWpG) & REGISTER TRANSFER (§§21/22 eWpG, §20 eWpRV)
-- ═══════════════════════════════════════════════════════════════════════════

-- §10 eWpG grants electronic inspection rights to participants: the issuer, the
-- holder, and — in single entry — anyone in whose favour a right is recorded. A
-- Berechtigter always has a legitimate interest (§10(2) eWpRV). Other applicants
-- must demonstrate a legitimate interest, which the operator reviews.
--
-- §§21/22 eWpG require the operator to be able to hand the register over to a
-- successor (e.g. when it can no longer meet the statutory requirements). §20
-- eWpRV requires the procedure and the data transfer to be documented. The
-- on-chain control handover already exists in the contracts; this records the
-- off-chain export and its status.

-- ── Register inspection requests (§10 eWpG) ───────────────────────────────────
CREATE TABLE register_inspection_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    -- The applicant; may be an onboarded entity or an external party.
    requester_entity_id UUID REFERENCES legal_entity(id),
    requester_name      TEXT NOT NULL,
    requester_email     TEXT,
    -- Asserted basis: ISSUER, HOLDER, BENEFICIARY (always legitimate, §10(2)
    -- eWpRV) or LEGITIMATE_INTEREST (reviewed by the operator).
    legal_basis         VARCHAR(30) NOT NULL,
    stated_interest     TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    -- REQUESTED, APPROVED, REJECTED, FULFILLED.
    decision_reason     TEXT,
    decided_by          UUID REFERENCES legal_entity(id),
    decided_at          TIMESTAMPTZ,
    fulfilled_at        TIMESTAMPTZ,
    content_hash        VARCHAR(66),   -- hash of the disclosed extract, when fulfilled
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_asset ON register_inspection_request (asset_id);
CREATE INDEX idx_inspection_status ON register_inspection_request (status)
    WHERE status IN ('REQUESTED', 'APPROVED');

-- ── Register transfer (§§21/22 eWpG, §20 eWpRV) ───────────────────────────────
CREATE TABLE register_transfer (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    -- Name / identifier of the successor registry operator.
    successor_name        TEXT NOT NULL,
    successor_identifier  TEXT,
    -- Reason for the handover (§22: e.g. operator can no longer meet requirements).
    reason                TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, EXPORTED, HANDED_OVER, COMPLETED, CANCELLED.
    -- The exported data package (the §20 eWpRV data transfer) and its hash.
    export_hash           VARCHAR(66),
    export_manifest       JSONB,
    -- On-chain control handover reference (links to the contract two-step handover).
    onchain_tx_hash       VARCHAR(66),
    initiated_by          UUID REFERENCES legal_entity(id),
    initiated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at           TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_register_transfer_asset ON register_transfer (asset_id);
CREATE INDEX idx_register_transfer_status ON register_transfer (status)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');

-- ═══════════════════════════════════════════════════════════════════════════
-- DSGVO ART. 17 ERASURE REQUESTS
-- ═══════════════════════════════════════════════════════════════════════════

-- The erasure endpoint previously acknowledged a request ("ERASURE_REQUESTED") without
-- storing anything, so a legally-binding request was dropped and no operator could act
-- on it. This table makes each request a persisted operator work item carrying the
-- 30-day response clock (Art. 12(3)) and its resolution. Actual erasure remains a manual,
-- reviewed step because most fields fall under statutory retention (eWpG §15(3), GwG §8).

CREATE TABLE erasure_request (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id            UUID           NOT NULL REFERENCES legal_entity(id),
    requested_by_user_id UUID,
    status               VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
    requested_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    due_at               TIMESTAMPTZ    NOT NULL,
    reviewed_by          UUID,
    reviewed_at          TIMESTAMPTZ,
    resolution_note      TEXT,
    CONSTRAINT chk_erasure_request_status CHECK (
        status IN ('REQUESTED','IN_REVIEW','COMPLETED','REJECTED')
    )
);

-- Operator queue: open requests, oldest first.
CREATE INDEX idx_erasure_request_open ON erasure_request (requested_at)
    WHERE status IN ('REQUESTED','IN_REVIEW');

-- Dedup lookup for repeated submissions by the same entity.
CREATE INDEX idx_erasure_request_entity ON erasure_request (entity_id, status);

-- ═══════════════════════════════════════════════════════════════════════════
-- ONCHAIN ORGANIZATION IDENTITY (ecosystem OrgRegistry mirror)
-- ═══════════════════════════════════════════════════════════════════════════

-- Every participant wallet belongs to exactly one organization per chain (SWIAT-style
-- "every user belongs to a company"). The org's onchain anchor is its ONCHAINID
-- (onchain_identity table); these tables mirror the OrgRegistry / PermissionRegistry
-- contracts so the backend can serve reads without RPC round-trips. The onchain state
-- is authoritative; a reconciliation job flags drift.

CREATE TABLE org_registration (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID           NOT NULL REFERENCES legal_entity(id),
    chain_config_id   UUID           NOT NULL REFERENCES chain_config(id),
    org_address       VARCHAR(66)    NOT NULL,
    -- ISO-3166-1 numeric; kept so a deferred/retried registerOrg broadcast carries it
    country_code      SMALLINT,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    registered_tx     VARCHAR(66),
    suspended_at      TIMESTAMPTZ,
    suspended_by      UUID,
    suspension_reason TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_org_registration_entity_chain UNIQUE (legal_entity_id, chain_config_id),
    CONSTRAINT chk_org_registration_status CHECK (
        status IN ('PENDING','ACTIVE','SUSPENDED','FAILED')
    )
);

CREATE INDEX idx_org_registration_status ON org_registration (status);

CREATE TABLE org_member_wallet (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_registration_id UUID           NOT NULL REFERENCES org_registration(id),
    chain_config_id     UUID           NOT NULL REFERENCES chain_config(id),
    wallet_address      VARCHAR(66)    NOT NULL,
    app_user_id         UUID,
    label               VARCHAR(120),
    roles               TEXT[]         NOT NULL DEFAULT '{}',
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    bound_tx            VARCHAR(66),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    removed_at          TIMESTAMPTZ,
    CONSTRAINT chk_org_member_wallet_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVED','FAILED')
    )
);

-- One live binding per wallet per chain (the OrgRegistry enforces the same onchain).
CREATE UNIQUE INDEX uq_org_member_wallet_live
    ON org_member_wallet (chain_config_id, lower(wallet_address))
    WHERE status IN ('PENDING','ACTIVE');

CREATE INDEX idx_org_member_wallet_org ON org_member_wallet (org_registration_id, status);

-- Short-lived nonce challenges proving control of a wallet before binding (EIP-191).
CREATE TABLE wallet_bind_challenge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    wallet_address  VARCHAR(66) NOT NULL,
    nonce           VARCHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_bind_challenge_lookup
    ON wallet_bind_challenge (legal_entity_id, chain_config_id, lower(wallet_address))
    WHERE used_at IS NULL;

-- Permission framework (PermissionRegistry mirror; grants managed from Phase 2 on).
CREATE TABLE permission_definition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(120) NOT NULL UNIQUE,
    permission_hash VARCHAR(66)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    dapp_listing_id UUID,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    defined_tx      VARCHAR(66),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_permission_definition_status CHECK (
        status IN ('DRAFT','ACTIVE','RETIRED')
    )
);

CREATE TABLE permission_grant (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_definition_id UUID        NOT NULL REFERENCES permission_definition(id),
    org_registration_id      UUID        NOT NULL REFERENCES org_registration(id),
    grant_type               VARCHAR(10) NOT NULL,
    role_code                VARCHAR(120),
    -- meaningful on ORG grants: when true, members additionally need a delegated role
    role_restricted          BOOLEAN     NOT NULL DEFAULT false,
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    granted_tx               VARCHAR(66),
    revoked_tx               VARCHAR(66),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at               TIMESTAMPTZ,
    -- 4-eyes: operator-tier permission grants/revocations carry step-up *and* a second
    -- approver, matching the asset_token_admin_grant pattern.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    CONSTRAINT chk_permission_grant_type CHECK (grant_type IN ('ORG','ROLE')),
    CONSTRAINT chk_permission_grant_status CHECK (
        status IN ('PENDING','ACTIVE','REVOKED','FAILED')
    ),
    CONSTRAINT chk_permission_grant_role CHECK (
        (grant_type = 'ROLE') = (role_code IS NOT NULL)
    )
);

CREATE INDEX idx_permission_grant_org ON permission_grant (org_registration_id, status);
CREATE INDEX idx_permission_grant_definition ON permission_grant (permission_definition_id, status);

-- Ecosystem-wide trusted claim issuers (EcosystemTrustedIssuersRegistry mirror).
CREATE TABLE ecosystem_trusted_issuer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    issuer_address  VARCHAR(66) NOT NULL,
    claim_topics    BIGINT[]    NOT NULL,
    legal_entity_id UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    added_tx        VARCHAR(66),
    removed_tx      VARCHAR(66),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at      TIMESTAMPTZ,
    -- 4-eyes, as for permission_grant above.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    CONSTRAINT chk_ecosystem_trusted_issuer_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVED','FAILED')
    )
);

CREATE UNIQUE INDEX uq_ecosystem_trusted_issuer_live
    ON ecosystem_trusted_issuer (chain_config_id, lower(issuer_address))
    WHERE status IN ('PENDING','ACTIVE');

-- ═══════════════════════════════════════════════════════════════════════════
-- PAYMENT RAILS (operator-curated payment methods for the cash leg)
-- ═══════════════════════════════════════════════════════════════════════════

-- The operator curates the payment methods the registry provides as ready-made rails:
-- MiCAR-compliant e-money-token stablecoins (AUEUR, USDC, …), the Pontes instant-payment
-- API, ERC-7573-style DvP settlement, and classic SEPA. dApp manifests reference rails
-- by code (advisory model — dApps may also declare custom methods they implement
-- themselves; the operator sees both at review time).

CREATE TABLE payment_rail (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(60)   NOT NULL UNIQUE,
    display_name        VARCHAR(200)  NOT NULL,
    rail_type           VARCHAR(20)   NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    decimals            INT,
    description         VARCHAR(1000),
    -- MiCAR metadata (rail_type = STABLECOIN): who issues the e-money token and under
    -- which authorization (Title IV EMT) — surfaced to publishers and investors.
    issuer_name         VARCHAR(200),
    issuer_lei          VARCHAR(20),
    micar_authorization VARCHAR(200),
    emt_flag            BOOLEAN       NOT NULL DEFAULT false,
    -- Holder-facing MiCAR Title IV disclosure (Art. 49 redemption, Art. 51 white paper) —
    -- disclosure-surfacing only; Registerwerk is not the EMT issuer.
    white_paper_url     VARCHAR(500),
    redemption_at_par   BOOLEAN       NOT NULL DEFAULT false,
    -- The fields above are operator-entered free text. These three record that an operator
    -- attested to having checked them, so "disclosed" is distinguishable from "actually
    -- checked against a real register". This is an auditable attestation, NOT a live
    -- register cross-check performed by Registerwerk.
    micar_verified      BOOLEAN       NOT NULL DEFAULT FALSE,
    micar_verified_at   TIMESTAMPTZ,
    micar_verified_by   UUID,
    enabled             BOOLEAN       NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_rail_type CHECK (
        rail_type IN ('STABLECOIN','PONTES_API','ERC7573_DVP','OFFCHAIN_SEPA')
    )
);

CREATE INDEX idx_payment_rail_enabled ON payment_rail (enabled);

-- Onchain deployment of a rail per chain (stablecoin token contract, DvP settlement
-- contract); API/off-chain rails have no rows here.
CREATE TABLE payment_rail_chain_address (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_rail_id UUID        NOT NULL REFERENCES payment_rail(id) ON DELETE CASCADE,
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    token_address   VARCHAR(66) NOT NULL,
    CONSTRAINT uq_payment_rail_chain UNIQUE (payment_rail_id, chain_config_id)
);

CREATE INDEX idx_payment_rail_chain_rail ON payment_rail_chain_address (payment_rail_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- DAPP MARKETPLACE (metadata-only listings)
-- ═══════════════════════════════════════════════════════════════════════════

-- Publishers submit a signed manifest describing their tokenization dApp (contracts,
-- required permissions/claims, container images pinned by OCI digest). The operator
-- reviews with 4-eyes; approval anchors keccak256(manifest_raw) in the onchain
-- DappRegistry. We store no artifacts — integrity comes from digests + the onchain hash.

CREATE TABLE dapp_listing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publisher_entity_id UUID         NOT NULL REFERENCES legal_entity(id),
    chain_config_id     UUID         NOT NULL REFERENCES chain_config(id),
    slug                VARCHAR(60)  NOT NULL UNIQUE,
    dapp_id_hash        VARCHAR(66)  NOT NULL,
    name                VARCHAR(200) NOT NULL,
    category            VARCHAR(60)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    current_version_id  UUID,
    contact_email       VARCHAR(320),
    docs_url            VARCHAR(500),
    pricing_note        VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dapp_listing_status CHECK (
        status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','DEPRECATED','DELISTED')
    )
);

CREATE INDEX idx_dapp_listing_status ON dapp_listing (status);
CREATE INDEX idx_dapp_listing_publisher ON dapp_listing (publisher_entity_id);

CREATE TABLE dapp_version (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id         UUID         NOT NULL REFERENCES dapp_listing(id),
    version            VARCHAR(40)  NOT NULL,
    -- verbatim manifest bytes — the keccak256 hash input; jsonb would normalize and break hashing
    manifest_raw       TEXT,
    -- normalized copy for querying only
    manifest_json      JSONB,
    manifest_hash      VARCHAR(66),
    manifest_signature VARCHAR(200),
    signer_wallet      VARCHAR(66),
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    review_notes       TEXT,
    submitted_at       TIMESTAMPTZ,
    reviewed_by        UUID,
    reviewed_at        TIMESTAMPTZ,
    onchain_tx         VARCHAR(66),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dapp_version_status CHECK (
        status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','SUPERSEDED')
    ),
    CONSTRAINT uq_dapp_version UNIQUE (listing_id, version)
);

CREATE INDEX idx_dapp_version_listing ON dapp_version (listing_id, created_at DESC);
CREATE INDEX idx_dapp_version_review_queue ON dapp_version (submitted_at)
    WHERE status IN ('SUBMITTED','IN_REVIEW');

CREATE TABLE dapp_required_permission (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      UUID         NOT NULL REFERENCES dapp_version(id) ON DELETE CASCADE,
    permission_code VARCHAR(120) NOT NULL,
    permission_hash VARCHAR(66)  NOT NULL,
    claim_topics    BIGINT[]     NOT NULL DEFAULT '{}',
    rationale       VARCHAR(500)
);

CREATE INDEX idx_dapp_required_permission_version ON dapp_required_permission (version_id);

-- Payment methods a dApp version declares in its manifest: either a reference to an
-- operator-curated payment_rail (by code — soft reference so rails can be disabled
-- without FK pain; re-checked at approval) or a custom method the dApp implements.
CREATE TABLE dapp_payment_method (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id         UUID        NOT NULL REFERENCES dapp_version(id) ON DELETE CASCADE,
    method_type        VARCHAR(10) NOT NULL,
    rail_code          VARCHAR(60),
    custom_name        VARCHAR(120),
    custom_description VARCHAR(500),
    currency           VARCHAR(3),
    note               VARCHAR(500),
    CONSTRAINT chk_dapp_payment_method_type CHECK (method_type IN ('RAIL','CUSTOM')),
    CONSTRAINT chk_dapp_payment_method_shape CHECK ((method_type = 'RAIL') = (rail_code IS NOT NULL))
);

CREATE INDEX idx_dapp_payment_method_version ON dapp_payment_method (version_id);

CREATE TABLE dapp_review_event (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID        NOT NULL REFERENCES dapp_version(id),
    action     VARCHAR(30) NOT NULL,
    actor_id   UUID,
    notes      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dapp_review_event_version ON dapp_review_event (version_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- SEED DATA
-- ═══════════════════════════════════════════════════════════════════════════

-- Chain registry — EVM mainnet/testnet
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES
('ETHEREUM_MAINNET',  'Ethereum Mainnet',      'EVM', 'MAINNET', 1,
 'https://mainnet.infura.io/v3/changeme', 'wss://mainnet.infura.io/ws/v3/changeme',
 'https://etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-mainnet'),
('ETHEREUM_SEPOLIA',  'Ethereum Sepolia',       'EVM', 'TESTNET', 11155111,
 'https://sepolia.infura.io/v3/changeme', 'wss://sepolia.infura.io/ws/v3/changeme',
 'https://sepolia.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-sepolia'),
('POLYGON_MAINNET',   'Polygon Mainnet',        'EVM', 'MAINNET', 137,
 'https://polygon-mainnet.infura.io/v3/changeme', 'wss://polygon-mainnet.infura.io/ws/v3/changeme',
 'https://polygonscan.com', 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-mainnet'),
('POLYGON_AMOY',      'Polygon Amoy Testnet',   'EVM', 'TESTNET', 80002,
 'https://polygon-amoy.infura.io/v3/changeme', 'wss://polygon-amoy.infura.io/ws/v3/changeme',
 'https://amoy.polygonscan.com', 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-amoy'),
('BASE_MAINNET',      'Base Mainnet',           'EVM', 'MAINNET', 8453,
 'https://mainnet.base.org', 'wss://mainnet.base.org',
 'https://basescan.org', 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-mainnet'),
('BASE_SEPOLIA',      'Base Sepolia Testnet',   'EVM', 'TESTNET', 84532,
 'https://sepolia.base.org', 'wss://sepolia.base.org',
 'https://sepolia.basescan.org', 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-sepolia'),
('SOLANA_MAINNET',    'Solana Mainnet Beta',    'SOLANA', 'MAINNET', NULL,
 'https://api.mainnet-beta.solana.com', 'wss://api.mainnet-beta.solana.com',
 'https://solscan.io', NULL, NULL),
('SOLANA_DEVNET',     'Solana Devnet',          'SOLANA', 'TESTNET', NULL,
 'https://api.devnet.solana.com', 'wss://api.devnet.solana.com',
 'https://solscan.io', NULL, NULL),
('FHENIX_MAINNET',    'Fhenix Mainnet',         'EVM', 'MAINNET', 21888,
 'https://api.fhenix.zone:7747', 'wss://api.fhenix.zone:7748',
 'https://explorer.fhenix.zone', NULL, NULL),
('FHENIX_HELIUM',     'Fhenix Helium Testnet',  'EVM', 'TESTNET', 8008135,
 'https://api.helium.fhenix.zone:7747', 'wss://api.helium.fhenix.zone:7748',
 'https://explorer.helium.fhenix.zone', NULL, NULL),
('INCO_MAINNET',      'Inco Mainnet',           'EVM', 'MAINNET', 9090,
 'https://mainnet.inco.org', 'wss://mainnet.inco.org',
 'https://explorer.inco.org', NULL, NULL),
('INCO_RIVEST',       'Inco Rivest Testnet',    'EVM', 'TESTNET', 21097,
 'https://validator.rivest.inco.org', 'wss://validator.rivest.inco.org',
 'https://explorer.rivest.inco.org', NULL, NULL),
('ARBITRUM_MAINNET',  'Arbitrum One',           'EVM', 'MAINNET', 42161,
 'https://arbitrum.publicnode.com', 'wss://arbitrum-one.publicnode.com',
 'https://arbiscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-mainnet'),
('ARBITRUM_SEPOLIA',  'Arbitrum Sepolia',       'EVM', 'TESTNET', 421614,
 'https://sepolia-rollup.arbitrum.io/rpc', 'wss://sepolia-rollup.arbitrum.io/ws',
 'https://sepolia.arbiscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-sepolia'),
('AVALANCHE_MAINNET', 'Avalanche C-Chain',      'EVM', 'MAINNET', 43114,
 'https://api.avax.network/ext/bc/C/rpc', 'wss://api.avax.network/ext/bc/C/ws',
 'https://snowtrace.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-mainnet'),
('AVALANCHE_FUJI',    'Avalanche Fuji Testnet', 'EVM', 'TESTNET', 43113,
 'https://api.avax-test.network/ext/bc/C/rpc', 'wss://api.avax-test.network/ext/bc/C/ws',
 'https://testnet.snowtrace.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-fuji'),
('OPTIMISM_MAINNET',  'Optimism',               'EVM', 'MAINNET', 10,
 'https://mainnet.optimism.io', 'wss://ws-mainnet.optimism.io',
 'https://optimistic.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-mainnet'),
('OPTIMISM_SEPOLIA',  'Optimism Sepolia',       'EVM', 'TESTNET', 11155420,
 'https://sepolia.optimism.io', 'wss://sepolia.optimism.io',
 'https://sepolia-optimism.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-sepolia');

-- Non-EVM chains (enabled=false — flip when client integration is complete)
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url, enabled) VALUES
('STARKNET_MAINNET', 'Starknet Mainnet', 'STARKNET', 'MAINNET', NULL,
 'https://rpc.starknet.lava.build', NULL, 'https://starkscan.co', false),
('STARKNET_SEPOLIA', 'Starknet Sepolia', 'STARKNET', 'TESTNET', NULL,
 'https://api.cartridge.gg/x/starknet/sepolia', NULL, 'https://sepolia.starkscan.co', false),
('STELLAR_MAINNET',  'Stellar Mainnet',  'STELLAR',  'MAINNET', NULL,
 'https://horizon.stellar.org', NULL, 'https://stellar.expert/explorer/public', false),
('STELLAR_TESTNET',  'Stellar Testnet',  'STELLAR',  'TESTNET', NULL,
 'https://horizon-testnet.stellar.org', NULL, 'https://stellar.expert/explorer/testnet', false),
('CANTON_MAINNET',   'Canton Mainnet',   'CANTON',   'MAINNET', NULL,
 '', NULL, 'https://canton.network', false),
('CANTON_DEVNET',    'Canton DevNet',    'CANTON',   'TESTNET', NULL,
 '', NULL, 'https://canton.network', false);

-- Canton synchronizer config
UPDATE chain_config
   SET application_id = 'registerwerk', synchronizer_id = 'global-synchronizer'
 WHERE identifier = 'CANTON_MAINNET';

UPDATE chain_config
   SET application_id = 'registerwerk', synchronizer_id = 'dev-synchronizer'
 WHERE identifier = 'CANTON_DEVNET';

-- Primary RPC nodes (skip chains with empty URL)
INSERT INTO rpc_node (chain_config_id, url, label, enabled)
SELECT id, rpc_url, 'Primary', true
FROM chain_config
WHERE rpc_url IS NOT NULL AND rpc_url <> '';

-- DORA Art. 28 — pre-populated ICT third-party register
INSERT INTO third_party_provider (name, category, criticality) VALUES
    ('Ethereum RPC (Infura/Alchemy)', 'BLOCKCHAIN_RPC',     'CRITICAL'),
    ('The Graph (Graph Node)',        'GRAPH_NODE',          'IMPORTANT'),
    ('Solana RPC',                    'BLOCKCHAIN_RPC',      'IMPORTANT'),
    ('Canton Synchronizer',           'BLOCKCHAIN_RPC',      'IMPORTANT'),
    ('Starknet RPC',                  'BLOCKCHAIN_RPC',      'STANDARD'),
    ('Stellar Horizon',               'BLOCKCHAIN_RPC',      'STANDARD'),
    ('AWS S3 (document storage)',     'CLOUD_PROVIDER',      'IMPORTANT'),
    ('OpenSanctions (screening)',     'SANCTIONS_SCREENING', 'CRITICAL'),
    ('SMTP mail relay',               'EMAIL_SERVICE',       'STANDARD')
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- SHEDLOCK — multi-instance safety for @Scheduled jobs
-- ═══════════════════════════════════════════════════════════════════════════
-- Every @Scheduled job in this codebase (on-chain tx pollers, indexers, reporting
-- crons) would otherwise run on EVERY backend instance with no coordination, so scaling
-- out to more than one instance for hot-failover would double-submit on-chain
-- transactions and double-process events. ShedLock serializes each job across
-- threads AND instances via this table (standard ShedLock JDBC schema).

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- LOGIN ATTEMPT TRACKING — multi-instance-safe brute-force throttle
-- ═══════════════════════════════════════════════════════════════════════════
-- A per-instance in-memory counter would silently multiply the effective lockout
-- threshold by the instance count behind a load balancer. This table makes the
-- counter shared and consistent across every instance.

CREATE TABLE login_attempt (
    login_key      VARCHAR(320) NOT NULL PRIMARY KEY,
    attempt_count  INT          NOT NULL DEFAULT 1,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE login_attempt IS
    'Shared brute-force counter for the built-in HS256 login (POST /api/v1/public/auth/login), '
    'keyed by normalized email. Rows are upserted per attempt and deleted on success; a row '
    'older than the configured lockout window is treated as expired (see LoginAttemptLimiter).';

-- ═══════════════════════════════════════════════════════════════════════════
-- GAS SPONSORSHIP POLICY — ERC-4337 EwpgPaymaster budgets
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE gas_sponsorship_policy (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID REFERENCES asset_deployment(id),
    issuer_id            UUID,
    sponsor              VARCHAR(20) NOT NULL,
    monthly_cap_eth      NUMERIC(38,18),
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_gas_sponsorship_sponsor CHECK (
        sponsor IN ('OPERATOR','ISSUER')
    ),
    CONSTRAINT chk_gas_sponsorship_scope CHECK (
        (asset_deployment_id IS NOT NULL AND issuer_id IS NULL)
        OR (asset_deployment_id IS NULL AND issuer_id IS NOT NULL)
    )
);

CREATE INDEX idx_gas_sponsorship_deployment ON gas_sponsorship_policy (asset_deployment_id);
CREATE INDEX idx_gas_sponsorship_issuer     ON gas_sponsorship_policy (issuer_id) WHERE asset_deployment_id IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- DORA Art. 24/25 — digital operational resilience testing
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE resilience_test (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_type               VARCHAR(30) NOT NULL,
    scope                   TEXT NOT NULL,
    tlpt_required           BOOLEAN NOT NULL DEFAULT FALSE,
    third_party_provider_id UUID REFERENCES third_party_provider(id),
    performed_at            DATE NOT NULL,
    next_due_date           DATE,
    result                  VARCHAR(20) NOT NULL,
    findings                TEXT,
    tester_name             TEXT,
    report_ref              TEXT,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_resilience_test_due ON resilience_test (next_due_date) WHERE next_due_date IS NOT NULL;
CREATE INDEX idx_resilience_test_provider ON resilience_test (third_party_provider_id) WHERE third_party_provider_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- LENDING MODULE — repo/collateralized-lending read-model
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Backs the `lending` module: a thin cache in front of the on-chain EwpgRepoMarket /
-- EwpgRepoVault view functions (see contracts/src/lending/), NOT a ledger. The contracts
-- remain the sole source of truth for balances/debt; these tables exist so the customer
-- frontend has a fast, queryable "my positions" / "markets" view without every page load
-- fanning out to eth_call for every known wallet across every market.

CREATE TABLE lending_market (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id          UUID         NOT NULL REFERENCES chain_config(id),
    market_address           VARCHAR(66)  NOT NULL,
    vault_address            VARCHAR(66),
    collateral_asset_id      UUID REFERENCES asset(id),
    collateral_token_address VARCHAR(66)  NOT NULL,
    loan_token_address       VARCHAR(66)  NOT NULL,
    loan_rail_code           VARCHAR(60)  REFERENCES payment_rail(code),
    lltv_bps                 INTEGER      NOT NULL,
    liquidation_bonus_bps    INTEGER      NOT NULL,
    base_rate_wad            NUMERIC(38,0) NOT NULL,
    slope_wad                NUMERIC(38,0) NOT NULL,
    price_oracle_address     VARCHAR(66)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    registered_by            UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lending_market_address UNIQUE (chain_config_id, market_address),
    CONSTRAINT chk_lending_market_status CHECK (status IN ('ACTIVE','PAUSED','RETIRED')),
    CONSTRAINT chk_lending_market_lltv CHECK (lltv_bps > 0 AND lltv_bps <= 10000)
);

CREATE INDEX idx_lending_market_chain ON lending_market (chain_config_id);
CREATE INDEX idx_lending_market_collateral_asset ON lending_market (collateral_asset_id);

-- Borrower positions — one row per (market, wallet), refreshed on demand by
-- LendingPositionService via a live debtOf/healthFactor/positions() read.
CREATE TABLE lending_position (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id         UUID         NOT NULL REFERENCES lending_market(id),
    wallet_address    VARCHAR(66)  NOT NULL,
    collateral_amount NUMERIC(78,0) NOT NULL DEFAULT 0,
    current_debt      NUMERIC(78,0) NOT NULL DEFAULT 0,
    health_factor_wad NUMERIC(78,0),
    -- Whether health_factor_wad can be trusted. NULL = not read (no debt, or the read itself
    -- failed); FALSE = read succeeded but the price backing it is unpriced or stale. Without
    -- this flag a stale mark is indistinguishable from a good one.
    health_factor_reliable BOOLEAN,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    last_synced_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lending_position UNIQUE (market_id, wallet_address),
    CONSTRAINT chk_lending_position_status CHECK (status IN ('OPEN','CLOSED','LIQUIDATED'))
);

CREATE INDEX idx_lending_position_wallet ON lending_position (wallet_address);
CREATE INDEX idx_lending_position_market ON lending_position (market_id);

-- Lender (supply-side) positions — one row per (market, wallet).
CREATE TABLE lending_supply_position (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id      UUID         NOT NULL REFERENCES lending_market(id),
    wallet_address VARCHAR(66)  NOT NULL,
    current_claim  NUMERIC(78,0) NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lending_supply_position UNIQUE (market_id, wallet_address)
);

CREATE INDEX idx_lending_supply_position_wallet ON lending_supply_position (wallet_address);
CREATE INDEX idx_lending_supply_position_market ON lending_supply_position (market_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- ASSET_TOKEN_ADMIN — delegatable forcedTransfer/forcedApprove/forceBurn grants
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Structural analogue of holder_block above — same lifecycle shape (create/revoke, legal
-- basis, 4-eyes, auto-expiry).

CREATE TABLE asset_token_admin_grant (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                UUID NOT NULL,
    asset_id                 UUID,
    wallet_address           TEXT NOT NULL,
    capability               VARCHAR(40) NOT NULL DEFAULT 'ASSET_TOKEN_ADMIN',
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    eligibility_basis        VARCHAR(40) NOT NULL,
    chain_config_id          UUID,
    legal_basis              TEXT NOT NULL,
    created_by               UUID NOT NULL,
    -- Set at GRANT time and never overwritten.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    expires_at               TIMESTAMPTZ,
    revoked_at               TIMESTAMPTZ,
    revoked_by               UUID,
    revoke_reason            TEXT,
    -- Revocation needs 4-eyes too, and needs its *own* pair: reusing the grant-time columns
    -- would erase the record of who approved the original grant.
    revoke_dual_control_approver_id UUID,
    revoke_dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_token_admin_grant_asset        ON asset_token_admin_grant (asset_id)  WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_entity        ON asset_token_admin_grant (entity_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_entity_asset   ON asset_token_admin_grant (entity_id, asset_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_expires        ON asset_token_admin_grant (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- CUSTOMER SUPPORT — support tickets + threaded messages
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Previously nothing existed — no ticketing, no support concept at all. A customer's only
-- "raise something with the operator" channel was DSAR erasure requests (a narrow, GDPR-specific
-- flow), and KYC document review.

CREATE TABLE support_ticket (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID NOT NULL REFERENCES legal_entity(id),
    created_by        UUID NOT NULL REFERENCES app_user(id),
    subject           VARCHAR(200) NOT NULL,
    description       TEXT NOT NULL,
    category          VARCHAR(30) NOT NULL,
    priority          VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_to       UUID REFERENCES app_user(id),
    resolution_notes  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    CONSTRAINT chk_support_ticket_category CHECK (
        category IN ('TECHNICAL','COMPLIANCE','BILLING','ASSET_ISSUE','TRADING','ONBOARDING','OTHER')
    ),
    CONSTRAINT chk_support_ticket_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT chk_support_ticket_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED'))
);

CREATE INDEX idx_support_ticket_entity ON support_ticket (entity_id);
CREATE INDEX idx_support_ticket_status ON support_ticket (status);
CREATE INDEX idx_support_ticket_assigned ON support_ticket (assigned_to) WHERE assigned_to IS NOT NULL;

CREATE TABLE support_ticket_message (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id        UUID NOT NULL REFERENCES support_ticket(id),
    author_id        UUID NOT NULL REFERENCES app_user(id),
    author_is_operator BOOLEAN NOT NULL,
    body             TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_ticket_message_ticket ON support_ticket_message (ticket_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- PORTFOLIO MIGRATION — investor off-ramp to a successor/competitor registrar
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Investor-side counterpart to register_transfer above (§§21/22 eWpG, asset-scoped): a
-- portfolio-migration request moves ONE investor's ONE holding out to a successor/competitor
-- registrar. Previously there was no holder-side "move my portfolio out" flow at all — only the
-- asset's own register/on-chain-control handover existed.

CREATE TABLE portfolio_migration_request (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    asset_id                 UUID NOT NULL REFERENCES asset(id),
    holder_id                UUID NOT NULL,
    destination_registrar_name TEXT,
    destination_registrar_identifier TEXT,
    destination_wallet_address TEXT,
    reason                   TEXT NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, EXPORTED, HANDED_OVER, COMPLETED, CANCELLED (mirrors register_transfer.status).
    export_hash              VARCHAR(66),
    export_manifest          JSONB,
    onchain_tx_hash          VARCHAR(66),
    initiated_by             UUID,
    initiated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at              TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_portfolio_migration_investor ON portfolio_migration_request (investor_entity_id);
CREATE INDEX idx_portfolio_migration_holder ON portfolio_migration_request (holder_id);
CREATE INDEX idx_portfolio_migration_status ON portfolio_migration_request (status)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V2__disable_unreachable_seed_rpc_nodes.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- V2 — stop the demo stack probing RPC endpoints that can never answer.
--
-- V1 seeds chain_config with public defaults and then derives one rpc_node row per chain
-- (V1__initial_schema.sql:2302-2305). Several of those endpoints are placeholders or dead
-- networks, so a stock `docker compose up` had RpcNodeHealthService issuing two blocking
-- JSON-RPC calls per node every 30 seconds against hosts that always time out — burning the
-- timeout budget and emitting a WARN per node per round, forever.
--
-- These rows are disabled, not deleted: the chain definitions stay visible in the operator
-- portal, and re-enabling one is a single UPDATE once a real endpoint is configured. Anyone
-- who has already replaced the URLs keeps their node enabled — the WHERE clauses match only
-- the literal seeded values.

-- 1. Infura placeholders — the seed literally ships `/v3/changeme`.
UPDATE rpc_node
SET    enabled = false
WHERE  url LIKE '%/v3/changeme';

-- 2. Fhenix and Inco. Both are experimental/withdrawn confidential-EVM testnets with no
--    reachable public endpoint; the token standards that target them are still exercised
--    against the Zama fhEVM path instead.
UPDATE rpc_node
SET    enabled = false
WHERE  url IN (
    'https://api.fhenix.zone:7747',
    'https://api.helium.fhenix.zone:7747',
    'https://mainnet.inco.org',
    'https://validator.rivest.inco.org'
);

-- 3. graph_node_url pointed 12 chains at http://graph-node:8000, a host that only exists if
--    indexer/evm/docker-compose.yml is started separately — and whose published ports 8000/8001
--    collide head-on with Kong's in the main stack, so the two cannot run side by side as
--    written. GraphNodeSyncService selects exactly the rows with a non-blank graph_node_url,
--    so this had it failing 12x every 30s until each chain hit its 10-consecutive-error ceiling.
--    Blanking the seed makes the subgraph indexer explicitly opt-in: set graph_node_url on the
--    chains you actually deploy a subgraph for.
UPDATE chain_config
SET    graph_node_url      = NULL,
       graph_subgraph_name = NULL
WHERE  graph_node_url = 'http://graph-node:8000/subgraphs/name';

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V3__wallet_keystore_blob.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- V3 — Postgres-backed wallet keystore storage.
--
-- WalletStorage previously wrote encrypted keystore/DEK-sidecar files only to a local
-- filesystem path (registerwerk.wallet.storage-dir, default /data/wallets). That makes the
-- backend node-affine: every replica needs the same files, so the Helm chart mounted one
-- ReadWriteOnce PVC into every pod — which cannot co-schedule with the chart's own required
-- pod anti-affinity (one replica per node). Replicas beyond the first hang on
-- FailedAttachVolume forever.
--
-- This table lets KeystoreBlobStore persist the same encrypted bytes (KEK-wrapped DEK +
-- ciphertext — nothing here is plaintext key material) in Postgres instead, which already
-- has its own HA/replication/backup story. Selected via
-- registerwerk.wallet.storage-backend=POSTGRES; the filesystem backend remains the default
-- for local/single-instance dev.

CREATE TABLE wallet_keystore_blob (
    relative_path VARCHAR(255) PRIMARY KEY,
    content       BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V4__asset_economic_terms.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Baseline economic terms on every asset, regardless of token standard — distinct from
-- asset_bond_terms, which only exists for bond-standard assets and is entered separately.
-- Without these, statements, valuations, tax reporting, and corporate actions have no
-- amount or currency to work from for any non-bond asset (ledger finding: F-BLOCKER-1).
ALTER TABLE asset
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN issue_size NUMERIC(38, 8),
    ADD COLUMN denomination NUMERIC(38, 8),
    ADD COLUMN issue_date DATE,
    ADD COLUMN maturity_date DATE;

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V5__blockchain_transaction_ops_note.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Lets an operator annotate a FAILED/TIMEOUT blockchain_transaction row once they've handled it
-- (usually out-of-band, via the chain's own tooling) — TIMEOUT is currently terminal with no
-- automated resubmit path (see the global transaction console: no nonce/calldata is captured at
-- submission time, so a safe gas-bump resubmit isn't implementable without also touching the
-- shared signing path in EvmContractService, which this migration deliberately does not do).
ALTER TABLE blockchain_transaction
    ADD COLUMN ops_note TEXT,
    ADD COLUMN ops_reviewed_at TIMESTAMPTZ,
    ADD COLUMN ops_reviewed_by UUID;

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V6__subscription_order.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Primary-market subscription/allocation flow (F-BLOCKER-3): previously the only way to create
-- a position was an issuer manually typing a wallet address into a dialog — no order, no
-- allocation, no investor confirmation.
CREATE TABLE subscription_order (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    investor_entity_id  UUID NOT NULL,
    wallet_address      TEXT NOT NULL,
    requested_amount    NUMERIC(38,18) NOT NULL,
    allocated_amount    NUMERIC(38,18),
    status              VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_at        TIMESTAMPTZ,
    allocated_by        UUID,
    confirmed_at        TIMESTAMPTZ,
    resulting_holder_id UUID,
    rejection_reason    TEXT
);

CREATE INDEX idx_subscription_order_asset ON subscription_order (asset_id);
CREATE INDEX idx_subscription_order_investor ON subscription_order (investor_entity_id);
CREATE INDEX idx_subscription_order_status ON subscription_order (asset_id, status);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V7__trade_execution_payment_declared_at.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Adds the AWAITING_SELLER_CONFIRMATION settlement status: a buyer declaring payment no longer
-- credits the register directly — the selling company must independently confirm receipt first.
ALTER TABLE trade_execution ADD COLUMN payment_declared_at TIMESTAMPTZ;

ALTER TABLE trade_execution ALTER COLUMN settlement_status TYPE VARCHAR(30);

ALTER TABLE trade_execution DROP CONSTRAINT chk_trade_execution_settlement_status;
ALTER TABLE trade_execution ADD CONSTRAINT chk_trade_execution_settlement_status CHECK (
    settlement_status IN ('PENDING','AWAITING_SELLER_CONFIRMATION','SETTLED','FAILED','CANCELLED','REFUNDED')
);

CREATE INDEX idx_trade_execution_awaiting_confirmation ON trade_execution (payment_declared_at)
    WHERE settlement_status = 'AWAITING_SELLER_CONFIRMATION';

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V8__mifid_classification.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- MiFID II client classification, suitability assessment, and per-asset target market
-- (F-BLOCKER-11): previously no retail/professional/ECP flag existed anywhere, no suitability
-- questionnaire, and no target-market restriction on an asset — an EU bank could not onboard a
-- client to this at all.

ALTER TABLE legal_entity ADD COLUMN client_category VARCHAR(30);
ALTER TABLE legal_entity ADD COLUMN client_category_classified_at TIMESTAMPTZ;
ALTER TABLE legal_entity ADD COLUMN client_category_classified_by UUID;
ALTER TABLE legal_entity ADD CONSTRAINT chk_legal_entity_client_category CHECK (
    client_category IS NULL OR client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
);

CREATE TABLE suitability_assessment (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                     UUID NOT NULL REFERENCES legal_entity(id),
    knowledge_experience          VARCHAR(20) NOT NULL,
    risk_tolerance                VARCHAR(20) NOT NULL,
    investment_horizon_years      INTEGER,
    financial_situation_adequate  BOOLEAN NOT NULL,
    notes                         TEXT,
    assessed_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    assessed_by                   UUID,
    CONSTRAINT chk_suitability_knowledge CHECK (knowledge_experience IN ('NONE', 'BASIC', 'ADVANCED')),
    CONSTRAINT chk_suitability_risk CHECK (risk_tolerance IN ('LOW', 'MEDIUM', 'HIGH'))
);
CREATE INDEX idx_suitability_assessment_entity ON suitability_assessment (entity_id, assessed_at DESC);

ALTER TABLE asset ADD COLUMN target_market_min_experience VARCHAR(20);
ALTER TABLE asset ADD CONSTRAINT chk_asset_target_market_min_experience CHECK (
    target_market_min_experience IS NULL OR target_market_min_experience IN ('NONE', 'BASIC', 'ADVANCED')
);

CREATE TABLE asset_target_market_category (
    asset_id         UUID NOT NULL REFERENCES asset(id),
    client_category  VARCHAR(30) NOT NULL,
    PRIMARY KEY (asset_id, client_category),
    CONSTRAINT chk_asset_target_market_category CHECK (
        client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
    )
);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V9__investor_limits.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Per-investor eligibility limits (F-BLOCKER-12): previously the only limits were ERC-3643
-- on-chain compliance modules, surfaced read-only and token-wide — no per-investor minimum
-- subscription, maximum holding, or lockup existed anywhere.

ALTER TABLE asset ADD COLUMN min_investment_amount NUMERIC(38, 8);
ALTER TABLE asset ADD COLUMN max_holding_amount NUMERIC(38, 8);

CREATE TABLE investor_limit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                  UUID NOT NULL REFERENCES asset(id),
    investor_entity_id        UUID NOT NULL,
    min_investment_override   NUMERIC(38, 8),
    max_holding_override      NUMERIC(38, 8),
    lockup_until              DATE,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                UUID,
    CONSTRAINT uq_investor_limit_asset_investor UNIQUE (asset_id, investor_entity_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V10__relationship_manager.sql
-- ═══════════════════════════════════════════════════════════════════════════
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

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V11__webhooks.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Outbound event webhooks (F-BLOCKER-2, integration surface): previously there was no way for
-- a bank's core banking, treasury, or data-warehouse systems to be told anything happened —
-- every consumer had to poll REST.

CREATE TABLE webhook_subscription (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID NOT NULL,
    url          TEXT NOT NULL,
    secret       TEXT NOT NULL,
    event_types  TEXT NOT NULL, -- comma-separated WebhookEventType names; empty = all curated types
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID
);
CREATE INDEX idx_webhook_subscription_entity ON webhook_subscription (entity_id);
CREATE INDEX idx_webhook_subscription_enabled ON webhook_subscription (entity_id, enabled);

CREATE TABLE webhook_delivery (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscription(id),
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_code   INTEGER,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_webhook_delivery_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);
CREATE INDEX idx_webhook_delivery_subscription ON webhook_delivery (subscription_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_pending ON webhook_delivery (status) WHERE status IN ('PENDING', 'FAILED');

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V12__idempotency_keys.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Idempotency-Key support (F-BLOCKER-2, integration surface): previously there was no
-- Idempotency-Key header contract on the mutating REST surface — a middleware team integrating
-- over an unreliable link had no safe retry story (a retried POST could double-execute).

CREATE TABLE idempotency_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_record_entity_key UNIQUE (entity_id, idempotency_key),
    CONSTRAINT chk_idempotency_record_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Input for the cleanup job — old records (of either status; a crashed IN_PROGRESS row must not
-- block that key forever) are purged after the retention window.
CREATE INDEX idx_idempotency_record_created_at ON idempotency_record (created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V13__generic_oidc_principal.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Generic (non-Entra) OIDC principal resolution (Track 6-4).
--
-- The JWKS decoder (JwtDecoderFactory.issuerBacked) already validates a token from ANY OIDC
-- issuer configured via JWT_ISSUER_URI, not just Entra. But DefaultPrincipalResolver used to
-- unconditionally treat every non-locally-minted token as an Entra token, resolving/provisioning
-- on Entra-specific claims (`oid`, `tid`) and mislabeling every such account UserAuthProvider.ENTRA
-- regardless of which issuer actually signed the token. A bank on Okta/Keycloak/ForgeRock/Auth0
-- whose tokens don't carry an `oid` claim would either be silently mislabeled or, if no
-- email/upn/preferred_username claim was present either, rejected outright with a 403 despite the
-- token cryptographically validating fine.
--
-- external_subject stores the OIDC `sub` claim for a principal resolved this way — the one
-- identifier every OIDC provider guarantees is stable, unlike Entra's `oid`.
ALTER TABLE app_user ADD COLUMN external_subject VARCHAR(255);

COMMENT ON COLUMN app_user.external_subject IS
    'OIDC `sub` claim for a principal resolved via a non-Entra OIDC issuer (JWT_ISSUER_URI). '
    'NULL for LOCAL and ENTRA accounts, which are keyed by id / entra_object_id respectively.';

CREATE UNIQUE INDEX ux_app_user_external_subject
    ON app_user (external_subject)
    WHERE external_subject IS NOT NULL;

ALTER TABLE app_user DROP CONSTRAINT chk_app_user_auth_provider;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_auth_provider CHECK (auth_provider IN ('LOCAL','ENTRA','OIDC'));

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V14__access_review.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Access recertification / entitlement review campaigns (Track 7-3).
--
-- BAIT (and every bank's IAM policy) requires periodic review and sign-off of user entitlements.
-- Previously there was no campaign tooling, no attestation record, and no "last reviewed"
-- timestamp on any account's roles — a REGISTRY_ADMIN's own role assignment was as unreviewed as
-- the day it was granted, no matter how long ago that was.

CREATE TABLE access_review_campaign (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    due_date    DATE,
    started_by  UUID         NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_by   UUID,
    closed_at   TIMESTAMPTZ,
    CONSTRAINT chk_access_review_campaign_status CHECK (status IN ('OPEN','CLOSED'))
);

-- One row per app_user, snapshotted at campaign start — the roles snapshot is what's actually
-- being attested to, independent of any role change the account undergoes mid-campaign.
CREATE TABLE access_review_item (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID         NOT NULL REFERENCES access_review_campaign(id) ON DELETE CASCADE,
    app_user_id         UUID         NOT NULL REFERENCES app_user(id),
    email_snapshot      VARCHAR(320) NOT NULL,
    full_name_snapshot  VARCHAR(200),
    roles_snapshot      VARCHAR(500) NOT NULL,
    decision            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by         UUID,
    reviewed_at         TIMESTAMPTZ,
    notes               TEXT,
    CONSTRAINT chk_access_review_item_decision CHECK (decision IN ('PENDING','CONFIRMED','REVOKED')),
    CONSTRAINT ux_access_review_item UNIQUE (campaign_id, app_user_id)
);

CREATE INDEX idx_access_review_item_campaign ON access_review_item (campaign_id);
CREATE INDEX idx_access_review_item_app_user ON access_review_item (app_user_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V15__travel_rule_inbox_idempotency.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Bind inbound replay protection to the originating VASP and its transfer reference.
-- Multiple counterparties may use the same reference, but one VASP must not create duplicate
-- compliance records by retrying the same delivery.
CREATE UNIQUE INDEX uq_trm_inbound_vasp_transfer_ref
    ON travel_rule_message (originator_vasp_did, protocol_message_id)
    WHERE direction = 'INBOUND' AND protocol_message_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V16__lending_market_verified_parameters.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Immutable EwpgRepoMarket parameters verified from chain at registration time. Existing rows
-- remain nullable and therefore fail closed for new borrowing until an operator re-registers or
-- reconciles them against the deployed contract; inventing a max-LTV during migration would be
-- materially unsafe.
ALTER TABLE lending_market
    ADD COLUMN max_ltv_bps INTEGER,
    ADD COLUMN max_price_age_seconds NUMERIC(78,0),
    ADD COLUMN liquidation_grace_period_seconds NUMERIC(78,0),
    ADD COLUMN loan_token_decimals INTEGER;

ALTER TABLE lending_market
    ADD CONSTRAINT chk_lending_market_max_ltv
        CHECK (max_ltv_bps IS NULL OR (max_ltv_bps > 0 AND max_ltv_bps < lltv_bps)),
    ADD CONSTRAINT chk_lending_market_loan_decimals
        CHECK (loan_token_decimals IS NULL OR (loan_token_decimals >= 0 AND loan_token_decimals <= 36));

-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V17__repo_desk_rfq.sql
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE repo_rfq (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    requester_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    requester_user_id UUID,
    side VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    collateral_quantity NUMERIC(38,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency CHAR(3) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    proposed_repo_rate NUMERIC(12,8),
    proposed_haircut_bps INTEGER,
    settlement_method VARCHAR(20) NOT NULL DEFAULT 'DVP',
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_repo_rfq_side CHECK (side IN ('BORROW_CASH', 'LEND_CASH')),
    CONSTRAINT ck_repo_rfq_visibility CHECK (visibility IN ('TARGETED', 'BROADCAST')),
    CONSTRAINT ck_repo_rfq_status CHECK (status IN ('OPEN', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_repo_rfq_settlement CHECK (settlement_method IN ('DVP', 'FOP')),
    CONSTRAINT ck_repo_rfq_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_rfq_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_rfq_dates CHECK (end_date > start_date),
    CONSTRAINT ck_repo_rfq_haircut CHECK (proposed_haircut_bps IS NULL OR proposed_haircut_bps BETWEEN 0 AND 10000)
);

CREATE TABLE repo_rfq_target (
    repo_rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    PRIMARY KEY (repo_rfq_id, target_entity_id)
);

CREATE TABLE repo_quote (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    quoting_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    quoting_user_id UUID,
    cash_amount NUMERIC(38,18) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_repo_quote_counterparty UNIQUE (rfq_id, quoting_entity_id),
    CONSTRAINT ck_repo_quote_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_quote_rate CHECK (repo_rate >= 0),
    CONSTRAINT ck_repo_quote_haircut CHECK (haircut_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_repo_quote_status CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'))
);

CREATE INDEX idx_repo_rfq_requester ON repo_rfq(requester_entity_id, created_at DESC);
CREATE INDEX idx_repo_rfq_open ON repo_rfq(status, expires_at);
CREATE INDEX idx_repo_rfq_target_entity ON repo_rfq_target(target_entity_id, repo_rfq_id);
CREATE INDEX idx_repo_quote_rfq ON repo_quote(rfq_id, created_at DESC);
CREATE INDEX idx_repo_quote_entity ON repo_quote(quoting_entity_id, created_at DESC);


-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V18__repo_trade_lifecycle.sql
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE repo_trade (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL UNIQUE REFERENCES repo_rfq(id),
    accepted_quote_id UUID NOT NULL UNIQUE REFERENCES repo_quote(id),
    cash_borrower_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    cash_lender_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    collateral_quantity NUMERIC(38,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency CHAR(3) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    repurchase_amount NUMERIC(38,18) NOT NULL,
    settlement_method VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    open_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    open_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    margin_call_amount NUMERIC(38,18),
    margin_call_due_at TIMESTAMPTZ,
    pending_substitution_asset_id UUID REFERENCES asset(id),
    pending_substitution_quantity NUMERIC(38,18),
    substitution_requested_by UUID REFERENCES legal_entity(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_repo_trade_status CHECK (status IN ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'CLOSED', 'DEFAULTED', 'CANCELLED')),
    CONSTRAINT ck_repo_trade_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_trade_cash CHECK (cash_amount > 0 AND repurchase_amount >= cash_amount),
    CONSTRAINT ck_repo_trade_parties CHECK (cash_borrower_entity_id <> cash_lender_entity_id),
    CONSTRAINT ck_repo_trade_haircut CHECK (haircut_bps BETWEEN 0 AND 10000)
);

CREATE TABLE repo_lifecycle_event (
    id UUID PRIMARY KEY,
    repo_trade_id UUID NOT NULL REFERENCES repo_trade(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    actor_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    actor_user_id UUID,
    amount NUMERIC(38,18),
    asset_id UUID REFERENCES asset(id),
    quantity NUMERIC(38,18),
    reference VARCHAR(200),
    note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_repo_trade_party_borrower ON repo_trade(cash_borrower_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_party_lender ON repo_trade(cash_lender_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_status ON repo_trade(status, end_date);
CREATE INDEX idx_repo_event_trade ON repo_lifecycle_event(repo_trade_id, created_at);


-- ═══════════════════════════════════════════════════════════════════════════
-- Schema changes originally introduced in V19__repo_currency_varchar.sql
-- ═══════════════════════════════════════════════════════════════════════════
-- Hibernate maps @Column(length=3) String as VARCHAR. PostgreSQL CHAR(3) pads values and is a
-- different JDBC type, so keep ISO currency validation while matching the entity mapping.
ALTER TABLE repo_rfq ALTER COLUMN cash_currency TYPE VARCHAR(3);
ALTER TABLE repo_trade ALTER COLUMN cash_currency TYPE VARCHAR(3);
