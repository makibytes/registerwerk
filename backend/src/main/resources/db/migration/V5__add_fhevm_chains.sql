ALTER TABLE asset
    DROP CONSTRAINT chk_asset_chain,
    ADD CONSTRAINT chk_asset_chain
        CHECK (chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM',
            'STARKNET','STELLAR','CANTON'
        ));

ALTER TABLE asset_deployment
    DROP CONSTRAINT chk_chain,
    ADD CONSTRAINT chk_chain CHECK (
        chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM',
            'STARKNET','STELLAR','CANTON'
        )
    );

ALTER TABLE trade_listing
    DROP CONSTRAINT chk_trade_listing_chain,
    ADD CONSTRAINT chk_trade_listing_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM',
            'STARKNET','STELLAR','CANTON'
        )
    );

ALTER TABLE trade_execution
    DROP CONSTRAINT chk_trade_execution_chain,
    ADD CONSTRAINT chk_trade_execution_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM',
            'STARKNET','STELLAR','CANTON'
        )
    );
