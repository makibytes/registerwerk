-- Fix (Phase 5 finding #4): only the 24h initial-report deadline (DORA Art. 19(4) / RTS
-- (EU) 2025/301) was tracked; the stricter 4h-from-classification deadline was never
-- modeled at all, so an incident could appear "on track" for up to 20h after the real,
-- binding deadline had already passed.
ALTER TABLE ict_incident
    ADD COLUMN classified_at          TIMESTAMPTZ,
    ADD COLUMN classification_deadline TIMESTAMPTZ;
