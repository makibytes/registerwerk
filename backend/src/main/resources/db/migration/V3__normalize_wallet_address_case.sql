-- Fix (LOW): wallet addresses were stored/queried as raw strings with no case
-- normalization, so indexer-persisted (lowercase) vs. UI-entered (possibly checksummed)
-- addresses for the same on-chain account could silently fail to match. HolderService and
-- EndpointService now normalize to lowercase at write time (EvmUtils.normalizeAddress,
-- matching indexer.api.HolderDataService's existing lower(Locale.ROOT) convention); this
-- backfills asset_holder rows that predate that change.
--
-- Only 0x-prefixed (EVM/Starknet) addresses are touched. Solana (base58) and Stellar
-- (base32) addresses — also stored in this column, never 0x-prefixed — are case-SENSITIVE
-- by construction; lowercasing those would corrupt them.
--
-- idx_holder_wallet is a UNIQUE index on (asset_id, wallet_address). Lowercasing two
-- differently-cased rows for the same asset that happen to represent the same address
-- would collide. That should never legitimately occur (it would mean the same wallet was
-- registered twice under different casing), but a migration must not crash the boot on
-- unexpected data — colliding groups are logged and left untouched for manual operator
-- review rather than updated or merged automatically.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT asset_id, lower(wallet_address) AS lower_address, array_agg(id) AS ids, count(*) AS cnt
        FROM asset_holder
        WHERE wallet_address ILIKE '0x%'
        GROUP BY asset_id, lower(wallet_address)
        HAVING count(*) > 1
    LOOP
        RAISE WARNING
            'V3__normalize_wallet_address_case: skipping % asset_holder row(s) % for asset_id=% '
            '— they collide on lower(wallet_address)=%; resolve manually before they can be normalized.',
            r.cnt, r.ids, r.asset_id, r.lower_address;
    END LOOP;
END $$;

UPDATE asset_holder h
SET wallet_address = lower(h.wallet_address)
WHERE h.wallet_address ILIKE '0x%'
  AND h.wallet_address <> lower(h.wallet_address)
  AND NOT EXISTS (
      SELECT 1
      FROM asset_holder h2
      WHERE h2.asset_id = h.asset_id
        AND lower(h2.wallet_address) = lower(h.wallet_address)
        AND h2.id <> h.id
  );
