-- A Solana Ed25519 transaction signature is normally 88 base58 characters. The old EVM-sized
-- varchar(66) made every successful Solana deployment fail while persisting its submission.
ALTER TABLE asset_deployment
    ALTER COLUMN deployed_by_tx TYPE varchar(128);
