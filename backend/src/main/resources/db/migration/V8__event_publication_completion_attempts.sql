-- Spring Modulith 2.x (v2 schema) added status, completion_attempts, and last_resubmission_date.
ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status                 TEXT,
    ADD COLUMN IF NOT EXISTS completion_attempts    INTEGER,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMP WITH TIME ZONE;

-- Improved lookup index from the v2 schema.
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
