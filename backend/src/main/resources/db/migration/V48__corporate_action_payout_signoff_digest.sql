-- Wave 0b C6: the issuer attestation and the operator confirmation of a corporate action's payout must cover the
-- COMPUTED amounts. Each is bound to a digest over (entries, total, rounding residual); the payout job only starts when
-- both digests equal the digest of the entries as they are NOW, and a re-snapshot / recompute voids both sign-offs.
ALTER TABLE corporate_action ADD COLUMN payout_digest             VARCHAR(64);
ALTER TABLE corporate_action ADD COLUMN issuer_attested_digest    VARCHAR(64);
ALTER TABLE corporate_action ADD COLUMN operator_confirmed_digest VARCHAR(64);

COMMENT ON COLUMN corporate_action.payout_digest IS
    'SHA-256 over the computed entitlements (entries, total, rounding residual) - set when the action reaches COMPUTED.';
COMMENT ON COLUMN corporate_action.issuer_attested_digest IS
    'payout_digest the issuer attested (or the operator overrode). Settlement requires it to equal the current digest.';
COMMENT ON COLUMN corporate_action.operator_confirmed_digest IS
    'payout_digest the operator confirmed. Settlement requires it to equal the current digest.';

-- Wave 0b H6: the payout gate pays eligible holders individually. A holder that fails the PartyEligibility gate at
-- payout time (entity not ACTIVE, KYC missing/expired, unresolved sanctions hit, Sperrvermerk) is HELD_BLOCKED with
-- the reason on the entry - recorded, excluded from the payable set, never paid, never silently dropped - instead of
-- stalling every other holder's payout.
ALTER TABLE corporate_action_entry ADD COLUMN held_reason TEXT;
-- migration-safety: ack (CHECK constraint is re-created immediately below with one added value; no data is dropped)
ALTER TABLE corporate_action_entry DROP CONSTRAINT ck_ca_entry_payout_status;
ALTER TABLE corporate_action_entry ADD CONSTRAINT ck_ca_entry_payout_status
    CHECK (payout_status IN ('PAYABLE', 'HELD_LOOK_THROUGH', 'HELD_BLOCKED'));

-- A COMPUTED action whose settlement the SYSTEM holds back (a Canton aggregate call that cannot exclude one holder,
-- a finality hold, a frozen register) carries the reason here, so the maturity job can tell a registry-side hold from
-- issuer non-payment and never turns the former into OVERDUE / DEFAULTED.
ALTER TABLE corporate_action ADD COLUMN settlement_hold_reason TEXT;

COMMENT ON COLUMN corporate_action_entry.held_reason IS
    'Why this entry was not paid (HELD_BLOCKED): the PartyEligibility reasons at payout time.';
COMMENT ON COLUMN corporate_action.settlement_hold_reason IS
    'Set while the system itself holds the settlement back (not the issuer / operator); cleared when it is released.';
