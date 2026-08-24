-- A chain effect is tied to one protocol block identity, not merely a height. The old
-- source_event_key omitted block_hash, so two same-height canonical incarnations could collapse
-- into one row. Canonicalise only hexadecimal identities: base58/base64-style identities used by
-- non-EVM chains are case-sensitive and must remain byte-for-byte distinct.
ALTER TABLE chain_effect ALTER COLUMN source_event_key TYPE VARCHAR(512);

-- Normalizing hexadecimal block/transaction identities can collapse two legacy source keys that
-- were distinct only by case. Fail before mutating any row so operators get a deterministic,
-- actionable diagnosis instead of a mid-UPDATE unique-constraint violation.
DO $$
DECLARE
    collision_detail TEXT;
BEGIN
    WITH projected AS (
        SELECT effect_type,
               entity_id,
               chain_config_id::text || ':' || block_number::text
                   || ':block=' || COALESCE(
                       CASE WHEN block_hash ~ '^0[xX][0-9A-Fa-f]+$'
                            THEN lower(block_hash) ELSE block_hash END, '~')
                   || ':tx=' || COALESCE(
                       CASE WHEN tx_hash ~ '^0[xX][0-9A-Fa-f]+$'
                            THEN lower(tx_hash) ELSE tx_hash END, '~')
                   || ':log=' || COALESCE(log_index::text, '~')
                   || ':occurrence=' || CASE
                       WHEN block_hash IS NULL AND tx_hash IS NULL
                           THEN COALESCE(correlation_id::text, '~')
                       ELSE '~'
                   END AS projected_source_event_key
        FROM chain_effect
    ), collision AS (
        SELECT effect_type, entity_id, projected_source_event_key, count(*) AS row_count
        FROM projected
        GROUP BY effect_type, entity_id, projected_source_event_key
        HAVING count(*) > 1
        ORDER BY effect_type, entity_id, projected_source_event_key
        LIMIT 1
    )
    SELECT format('effect_type=%s entity_id=%s projected_key=%s rows=%s',
                  effect_type, entity_id, projected_source_event_key, row_count)
    INTO collision_detail
    FROM collision;

    IF collision_detail IS NOT NULL THEN
        RAISE EXCEPTION USING
            MESSAGE = 'V20 cannot normalize chain_effect source identities without data loss',
            DETAIL = collision_detail,
            HINT = 'Inspect the colliding legacy effects and remove only a proven duplicate before retrying migration.';
    END IF;
END $$;

UPDATE chain_effect
SET block_hash = lower(block_hash)
WHERE block_hash ~ '^0[xX][0-9A-Fa-f]+$';

UPDATE chain_effect
SET tx_hash = lower(tx_hash)
WHERE tx_hash ~ '^0[xX][0-9A-Fa-f]+$';

UPDATE chain_effect
SET source_event_key = chain_config_id::text || ':' || block_number::text
    || ':block=' || COALESCE(block_hash, '~')
    || ':tx=' || COALESCE(tx_hash, '~')
    || ':log=' || COALESCE(log_index::text, '~')
    || ':occurrence=' || CASE
        WHEN block_hash IS NULL AND tx_hash IS NULL THEN COALESCE(correlation_id::text, '~')
        ELSE '~'
    END;

ALTER TABLE chain_effect
    ADD CONSTRAINT chk_chain_effect_normalized_hex_block_hash CHECK (
        block_hash IS NULL
        OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR block_hash = lower(block_hash)
    ),
    ADD CONSTRAINT chk_chain_effect_normalized_hex_tx_hash CHECK (
        tx_hash IS NULL
        OR tx_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR tx_hash = lower(tx_hash)
    );

-- PostgreSQL CURRENT_TIMESTAMP is the transaction-start timestamp, so recorded_at cannot order
-- two effects written in one transaction. A sequence is monotonic for committed inserts (gaps on
-- rollback are harmless) and gives compensation sweeps an unambiguous LIFO order.
CREATE SEQUENCE chain_effect_journal_sequence_seq AS BIGINT;

ALTER TABLE chain_effect ADD COLUMN journal_sequence BIGINT;

WITH historical_order AS (
    SELECT id, row_number() OVER (ORDER BY recorded_at, id)::BIGINT AS sequence_number
    FROM chain_effect
)
UPDATE chain_effect effect
SET journal_sequence = historical_order.sequence_number
FROM historical_order
WHERE effect.id = historical_order.id;

SELECT setval(
    'chain_effect_journal_sequence_seq',
    COALESCE((SELECT max(journal_sequence) FROM chain_effect), 0) + 1,
    FALSE
);

ALTER SEQUENCE chain_effect_journal_sequence_seq OWNED BY chain_effect.journal_sequence;

ALTER TABLE chain_effect
    ALTER COLUMN journal_sequence SET DEFAULT nextval('chain_effect_journal_sequence_seq'),
    ALTER COLUMN journal_sequence SET NOT NULL,
    ADD CONSTRAINT uq_chain_effect_journal_sequence UNIQUE (journal_sequence);

CREATE INDEX idx_chain_effect_chain_block_identity_sequence
    ON chain_effect (chain_config_id, block_hash, journal_sequence DESC);
