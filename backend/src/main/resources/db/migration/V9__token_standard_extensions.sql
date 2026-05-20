-- Widen token_standard column to accommodate new longer enum values (e.g. SPL_2022_CONFIDENTIAL)
ALTER TABLE asset ALTER COLUMN token_standard TYPE VARCHAR(30);

-- Bond terms — covers DAML_BOND_*, SPL_2022_BOND, and future EVM bond standards
CREATE TABLE asset_bond_terms (
    asset_id           UUID        PRIMARY KEY REFERENCES asset(id),
    face_value         NUMERIC(38, 18) NOT NULL,
    currency_iso       VARCHAR(3)  NOT NULL,
    issue_date         DATE        NOT NULL,
    maturity_date      DATE        NOT NULL,
    coupon_rate        NUMERIC(10, 8),       -- fixed bonds only
    reference_rate     VARCHAR(32),          -- floating bonds (e.g. EURIBOR_3M)
    spread             NUMERIC(10, 8),       -- floating bonds
    day_count          VARCHAR(20) NOT NULL,
    payment_frequency  VARCHAR(16) NOT NULL,
    callable           BOOLEAN     NOT NULL DEFAULT FALSE,
    call_schedule      JSONB,               -- [{callDate, callPrice}]
    bond_status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Vault state — covers ERC4626 (sync) and ERC7540 (async) tokenized vaults
CREATE TABLE asset_vault_state (
    asset_id                UUID        PRIMARY KEY REFERENCES asset(id),
    underlying_asset_id     UUID        REFERENCES asset(id),
    deposit_cap             NUMERIC(78, 0),
    min_settlement_delay    INTEGER,         -- seconds; ERC7540 only
    latest_nav_per_share    NUMERIC(38, 18),
    latest_nav_strike_at    TIMESTAMPTZ,
    latest_nav_report_hash  BYTEA,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- NAV strike history — append-only record of every NAV strike for a vault
CREATE TABLE vault_nav_strike (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID        NOT NULL REFERENCES asset(id),
    strike_id       BIGINT      NOT NULL,
    nav_per_share   NUMERIC(38, 18) NOT NULL,
    effective_at    TIMESTAMPTZ NOT NULL,
    report_hash     BYTEA,
    report_doc_id   UUID,
    struck_by       UUID        NOT NULL,
    struck_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tx_hash         VARCHAR(80),
    UNIQUE (asset_id, strike_id)
);

-- Async vault request queue — ERC7540 deposit/redeem requests mirrored off-chain
CREATE TABLE vault_request (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID        NOT NULL REFERENCES asset(id),
    request_id       NUMERIC(78, 0) NOT NULL,
    request_type     VARCHAR(8)  NOT NULL,  -- DEPOSIT | REDEEM
    controller_addr  VARCHAR(80) NOT NULL,
    owner_addr       VARCHAR(80) NOT NULL,
    asset_amount     NUMERIC(78, 0),        -- relevant for DEPOSIT requests
    share_amount     NUMERIC(78, 0),        -- relevant for REDEEM requests
    request_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fulfilled_at     TIMESTAMPTZ,
    fulfilled_tx     VARCHAR(80),
    nav_at_fulfill   NUMERIC(38, 18),
    UNIQUE (asset_id, request_id)
);

-- ERC-3525 / Starknet SFT slot registry
CREATE TABLE asset_slot (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id    UUID        NOT NULL REFERENCES asset(id),
    slot_id     NUMERIC(78, 0) NOT NULL,
    name        VARCHAR(200),
    metadata    JSONB,               -- coupon, ISIN sub-series, tranche info (off-chain only)
    supply_cap  NUMERIC(78, 0),
    paused      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, slot_id)
);

-- Per-token freeze state for ERC-3525 / Starknet SFT tokens
CREATE TABLE asset_token_unit (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      UUID        NOT NULL REFERENCES asset(id),
    slot_id       NUMERIC(78, 0) NOT NULL,
    token_id      NUMERIC(78, 0) NOT NULL,
    owner_addr    VARCHAR(80),
    token_value   NUMERIC(78, 0) NOT NULL DEFAULT 0,
    frozen        BOOLEAN     NOT NULL DEFAULT FALSE,
    freeze_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, token_id)
);

-- Coupon payment schedule + history — used by bonds across Canton, Solana, and future EVM
CREATE TABLE asset_coupon_payment (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID        NOT NULL REFERENCES asset(id),
    slot_id          NUMERIC(78, 0),         -- ERC-3525 tranche; NULL for single-series bonds
    period_no        INTEGER     NOT NULL,
    scheduled_date   DATE        NOT NULL,
    paid_date        DATE,
    amount_per_unit  NUMERIC(38, 18),
    coupon_status    VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    tx_ref           VARCHAR(120),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ON vault_request (asset_id, request_status);
CREATE INDEX ON asset_slot (asset_id);
CREATE INDEX ON asset_token_unit (asset_id, slot_id);
CREATE INDEX ON asset_coupon_payment (asset_id, coupon_status);
CREATE INDEX ON vault_nav_strike (asset_id, effective_at DESC);
