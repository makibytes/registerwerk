-- H11: V31 gave every lending market binding_verified DEFAULT TRUE, so legacy markets counted as verified
-- without any on-chain check. Fail closed: the default becomes FALSE and only rows with a recorded
-- successful verification (binding_verified_at is set together with a successful registration or
-- re-verification) stay TRUE. The operator re-verifies the rest via POST /api/v1/lending/markets/reverify and
-- pauses unverified / legacy markets on-chain via POST /api/v1/lending/markets/legacy-borrow-pause.
ALTER TABLE lending_market ALTER COLUMN binding_verified SET DEFAULT FALSE;

UPDATE lending_market
   SET binding_verified = FALSE
 WHERE binding_verified = TRUE
   AND binding_verified_at IS NULL;
