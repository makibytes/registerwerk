-- Review phase 3, T3-15: §16 eWpG Sperrvermerk wallets were stored exactly as typed. The register
-- (asset_holder) stores 0x addresses lowercased, and the gate and the on-chain freeze listener
-- compare exact strings, so a checksum-cased block froze nothing and the gates failed open.
-- The application now normalises on write (shared.AddressNormalizer: trim, lowercase 0x only —
-- base58/base32 addresses are case-sensitive and stay as they are). This backfills older rows.

-- ACTIVE blocks whose wallet changes were never propagated on-chain. Remember them so
-- kyc.internal.HolderBlockFreezeResyncRunner re-emits the freeze once after deploy.
CREATE TABLE holder_block_freeze_resync (
    holder_block_id          UUID PRIMARY KEY REFERENCES holder_block (id),
    original_wallet_address  TEXT        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at             TIMESTAMPTZ
);

INSERT INTO holder_block_freeze_resync (holder_block_id, original_wallet_address)
SELECT id, wallet_address
FROM holder_block
WHERE status = 'ACTIVE'
  AND wallet_address ~* '^\s*0x'
  AND wallet_address <> lower(btrim(wallet_address, E' \t\r\n'));

UPDATE holder_block
SET wallet_address = lower(btrim(wallet_address, E' \t\r\n')),
    updated_at     = now()
WHERE wallet_address ~* '^\s*0x'
  AND wallet_address <> lower(btrim(wallet_address, E' \t\r\n'));
