-- Review finding P4-05: vault request ids are counters of one vault contract, so the same asset
-- deployed on two chains produces colliding (asset_id, request_id) pairs. The key becomes
-- (asset_id, chain_config_id, request_id). The change only relaxes the old constraint, so every
-- existing row stays valid.

-- 1) Backfill chain_config_id: an asset with exactly one chain-bound deployment.
UPDATE vault_request vr
   SET chain_config_id = d.chain_config_id
  FROM (SELECT asset_id, MIN(chain_config_id::text)::uuid AS chain_config_id
          FROM asset_deployment
         WHERE chain_config_id IS NOT NULL
         GROUP BY asset_id
        HAVING COUNT(DISTINCT chain_config_id) = 1) d
 WHERE vr.chain_config_id IS NULL
   AND vr.asset_id = d.asset_id;

-- 2) Remaining rows of multi-chain assets: the chain of the fulfil/cancel tx the backend submitted.
UPDATE vault_request vr
   SET chain_config_id = t.chain_config_id
  FROM (SELECT DISTINCT ON (lower(tx_hash)) lower(tx_hash) AS tx_hash, chain_config_id
          FROM blockchain_transaction
         WHERE chain_config_id IS NOT NULL AND tx_hash IS NOT NULL
         ORDER BY lower(tx_hash), created_at DESC) t
 WHERE vr.chain_config_id IS NULL
   AND t.tx_hash IN (lower(vr.fulfilled_tx), lower(vr.cancelled_tx));

-- Rows still NULL (multi-chain legacy/demo rows with no submitted tx) are claimed by the first
-- chain-aware ingest/admin touch; the index treats NULL as its own bucket until then.
-- migration-safety: ack (replaced immediately below by a strictly weaker unique index; existing rows stay valid)
ALTER TABLE vault_request DROP CONSTRAINT vault_request_asset_id_request_id_key;

CREATE UNIQUE INDEX uq_vault_request_key
    ON vault_request (asset_id, COALESCE(chain_config_id, '00000000-0000-0000-0000-000000000000'::uuid), request_id);
