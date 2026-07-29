-- healthFactor() (finding #8, Phase 7) no longer reverts on an unpriced/stale mark — it now
-- returns an explicit reliability flag alongside the factor instead of a misleading bare value.
-- NULL here means "not read" (no debt, or the read itself failed), distinct from false ("read
-- succeeded but the price backing it is unpriced or stale — do not trust health_factor_wad").
ALTER TABLE lending_position ADD COLUMN health_factor_reliable BOOLEAN;
