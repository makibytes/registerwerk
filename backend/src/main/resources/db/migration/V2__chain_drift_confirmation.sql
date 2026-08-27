-- ChainDriftDetectionJob previously surfaced a registry-vs-chain balance mismatch to operators
-- the moment it was first observed. Right after a fresh boot (or any restart), token_transfer
-- can legitimately lag the registry by one detection cycle while Chaincache's durable event
-- subscription is still catching up on a chain's history — every holder touched during that
-- window looked like 100% drift. `confirmed` gates the two operator-visible surfaces (the
-- registerwerk_chain_drift_open_total gauge and the "Open" queue) on a divergence having
-- survived a second, independent detection run before anyone is asked to act on it.
-- `first_detected_at` is the immutable counterpart to the already-refreshed `detected_at`, so a
-- confirmed case still shows how long it has actually persisted.
ALTER TABLE chain_drift_event
    ADD COLUMN confirmed         BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN first_detected_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Existing rows were already surfaced to an operator under the old always-visible behavior;
-- treat them as already confirmed so this upgrade does not retroactively hide a divergence a
-- human may already be tracking. Only detections made after this migration go through the new
-- confirm-on-reconfirmation gate.
UPDATE chain_drift_event SET confirmed = true, first_detected_at = detected_at;

CREATE INDEX idx_drift_confirmed_open ON chain_drift_event (status) WHERE status = 'OPEN' AND confirmed = true;
