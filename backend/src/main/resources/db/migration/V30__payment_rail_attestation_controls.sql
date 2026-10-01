-- 5A-11: payment-rail separation of duties and content-bound MiCAR attestation.
--
-- created_by / updated_by let the attester be checked against whoever created or last
-- changed the rail (an operator must not attest their own entries). NULL on legacy rows.
-- micar_attested_fingerprint binds the attestation to the exact facts that were attested
-- (SHA-256 over a canonical string incl. chain token addresses, see PaymentRailAttestation):
-- an attestation whose fingerprint no longer matches is treated as void. Legacy rows have
-- micar_verified = true but no fingerprint, so they are void until re-attested (fail closed).
-- disabled_reason records why a rail was switched off automatically (attestation cleared on
-- an EMT rail) so that operators and the marketplace can show a banner instead of a silent gap.
ALTER TABLE payment_rail
    ADD COLUMN created_by                  UUID,
    ADD COLUMN updated_by                  UUID,
    ADD COLUMN micar_attested_fingerprint  VARCHAR(64),
    ADD COLUMN disabled_reason             VARCHAR(100);
