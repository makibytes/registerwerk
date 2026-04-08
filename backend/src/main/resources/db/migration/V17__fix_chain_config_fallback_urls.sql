-- Entity maps fallback_rpc_urls as comma-separated TEXT, not a PG array.
-- Convert the column from TEXT[] to TEXT.
ALTER TABLE chain_config
    ALTER COLUMN fallback_rpc_urls TYPE TEXT USING array_to_string(fallback_rpc_urls, ',');
