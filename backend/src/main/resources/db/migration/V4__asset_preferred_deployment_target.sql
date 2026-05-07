ALTER TABLE asset
    ADD COLUMN chain VARCHAR(20),
    ADD COLUMN network VARCHAR(10);

ALTER TABLE asset
    ADD CONSTRAINT chk_asset_chain
        CHECK (chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM',
            'STARKNET','STELLAR','CANTON'
        )),
    ADD CONSTRAINT chk_asset_network
        CHECK (network IS NULL OR network IN ('MAINNET','TESTNET')),
    ADD CONSTRAINT chk_asset_chain_network_pair
        CHECK (
            (chain IS NULL AND network IS NULL)
            OR (chain IS NOT NULL AND network IS NOT NULL)
        );

CREATE INDEX idx_asset_chain_network ON asset (chain, network) WHERE chain IS NOT NULL;
