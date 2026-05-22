-- ─────────────────────────────────────────────────────────────────────────────
-- V2: Extend token-standard CHECK constraints to include securities-grade
--     standards added in the feat/modulith release (ERC-3525, ERC-4626,
--     ERC-7540, Starknet SFT, DAML Finance bonds, SPL-2022 extension presets).
-- ─────────────────────────────────────────────────────────────────────────────

-- ── asset ─────────────────────────────────────────────────────────────────────
ALTER TABLE asset DROP CONSTRAINT IF EXISTS chk_token_standard;
ALTER TABLE asset ADD CONSTRAINT chk_token_standard CHECK (
    token_standard IN (
        'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
        'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
        -- securities-grade additions
        'ERC3525','ERC4626','ERC7540',
        'STARKNET_ERC3525',
        'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
        'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
    )
);

-- ── trade_listing ─────────────────────────────────────────────────────────────
ALTER TABLE trade_listing DROP CONSTRAINT IF EXISTS chk_trade_listing_token_standard;
ALTER TABLE trade_listing ADD CONSTRAINT chk_trade_listing_token_standard CHECK (
    token_standard IN (
        'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
        'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
        'ERC3525','ERC4626','ERC7540',
        'STARKNET_ERC3525',
        'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
        'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
    )
);

-- ── trade_execution ───────────────────────────────────────────────────────────
ALTER TABLE trade_execution DROP CONSTRAINT IF EXISTS chk_trade_execution_token_standard;
ALTER TABLE trade_execution ADD CONSTRAINT chk_trade_execution_token_standard CHECK (
    token_standard IN (
        'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
        'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
        'ERC3525','ERC4626','ERC7540',
        'STARKNET_ERC3525',
        'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
        'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
    )
);
