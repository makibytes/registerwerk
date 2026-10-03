-- Wave 0b C5: register amounts are RAW token base units (asset_holder.nominal_amount and token_transfer.amount are
-- written unscaled by the indexer), but coupon / redemption maths, the subscription mint and trading all assume WHOLE
-- units. Bond / fund tokens are therefore deployed with decimals = 0 and every register-unit flow fails closed
-- (RegisterUnits) on a deployment that does not report exactly 0. The deployment row now records the decimals the
-- token was deployed with; NULL means "unknown" and is refused like any other non-zero value.
ALTER TABLE asset_deployment ADD COLUMN token_decimals INTEGER;

COMMENT ON COLUMN asset_deployment.token_decimals IS
    'Decimals of the deployed token (0 = whole-unit register token). NULL = unknown (refused by RegisterUnits).';

-- Rows that already exist were deployed by the pre-C5 code: record what that code actually deployed, so the guard
-- refuses them with an accurate reason instead of an "unknown". Integer-only contracts have no fractional unit at all.
UPDATE asset_deployment d
   SET token_decimals = CASE a.token_standard
       WHEN 'ERC721'            THEN 0
       WHEN 'ERC1155'           THEN 0
       WHEN 'ERC3525'           THEN 0
       WHEN 'DAML_BOND_FIXED'   THEN 0
       WHEN 'DAML_BOND_FLOATING' THEN 0
       WHEN 'DAML_BOND_ZERO'    THEN 0
       WHEN 'ERC20'             THEN 18
       WHEN 'ERC3643'           THEN 18
       WHEN 'STARKNET_ERC20'    THEN 18
       WHEN 'STARKNET_ERC3525'  THEN 18
       WHEN 'STELLAR_ASSET'     THEN 7
       WHEN 'SPL'               THEN 6
       WHEN 'SPL_2022'          THEN 6
       WHEN 'SPL_2022_BOND'     THEN 6
       WHEN 'SPL_2022_CONFIDENTIAL' THEN 6
       ELSE NULL
   END
  FROM asset a
 WHERE a.id = d.asset_id;
