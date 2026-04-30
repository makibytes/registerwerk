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

CREATE INDEX idx_trade_listing_status_created
    ON trade_listing (status, created_at DESC);

CREATE INDEX idx_trade_listing_seller
    ON trade_listing (seller_entity_id, created_at DESC);

CREATE INDEX idx_trade_listing_asset
    ON trade_listing (asset_id, created_at DESC);

CREATE INDEX idx_trade_listing_holder
    ON trade_listing (seller_holder_id);

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

CREATE INDEX idx_trade_execution_buyer
    ON trade_execution (buyer_entity_id, created_at DESC);

CREATE INDEX idx_trade_execution_seller
    ON trade_execution (seller_entity_id, created_at DESC);

CREATE INDEX idx_trade_execution_listing
    ON trade_execution (listing_id);

CREATE INDEX idx_trade_execution_seller_pending
    ON trade_execution (seller_holder_id, settlement_status)
    WHERE settlement_status = 'PENDING';
