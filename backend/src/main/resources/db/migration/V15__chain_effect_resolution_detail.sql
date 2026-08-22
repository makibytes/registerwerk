-- Review-pass cleanup of the chain_effect journal (see the "post-P11 hardening" plan, Phase 1):
--
-- last_error was written on every terminal outcome, not just failures — a COMPENSATED row whose
-- compensator found the effect already undone (CompensationOutcome.NotApplicable) stored its
-- benign explanation in a column literally named "error", which the operator queue would
-- reasonably render as if something had gone wrong. Renamed to resolution_detail, which is
-- accurate for all four terminal outcomes (Compensated/NotApplicable/Failed/Irreversible).
ALTER TABLE chain_effect RENAME COLUMN last_error TO resolution_detail;

-- Lets ChainEffectRepository#claimForCompensation reclaim a row stuck in COMPENSATING because the
-- JVM that claimed it crashed mid-compensate — previously such a row was claimable only from
-- ACTIVE/COMPENSATION_FAILED and would sit COMPENSATING forever.
ALTER TABLE chain_effect ADD COLUMN claimed_at TIMESTAMPTZ;
