-- Add Canton-specific columns to chain_config.
-- STARKNET, STELLAR, CANTON chain_type values are already permitted by V3 constraints.
-- CANTON_MAINNET and CANTON_DEVNET rows were seeded in V3 (enabled=false).

ALTER TABLE chain_config ADD COLUMN IF NOT EXISTS application_id  VARCHAR(255);
ALTER TABLE chain_config ADD COLUMN IF NOT EXISTS synchronizer_id VARCHAR(255);

UPDATE chain_config
   SET application_id  = 'registerwerk',
       synchronizer_id = 'global-synchronizer'
 WHERE identifier = 'CANTON_MAINNET';

UPDATE chain_config
   SET application_id  = 'registerwerk',
       synchronizer_id = 'dev-synchronizer'
 WHERE identifier = 'CANTON_DEVNET';
