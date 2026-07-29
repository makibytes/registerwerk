-- Finding #3, Phase 10: SolanaTransferSyncService previously shared ONE polling cursor
-- (indexer_state, keyed only by chain+indexer_type) across every tracked mint on a chain, and
-- that cursor was only ever set once (the very first poll), never advanced again — permanently
-- freezing the "until" boundary and silently losing transfer history for active mints once the
-- backlog exceeded MAX_SIGNATURES_PER_POLL (200). This table gives each mint its own durable,
-- correctly-advancing cursor.
CREATE TABLE solana_mint_sync_cursor (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    mint_address          VARCHAR(64) NOT NULL,
    last_synced_signature VARCHAR(200),
    last_synced_at        TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_solana_mint_sync_cursor UNIQUE (chain_config_id, mint_address)
);
