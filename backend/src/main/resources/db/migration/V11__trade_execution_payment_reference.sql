-- Settling a PENDING trade previously required nothing beyond the buyer's own HTTP call — no
-- evidence of the actual payment (finding #2, Phase 7). The buyer must now supply a payment
-- reference (a stablecoin tx hash, a SEPA transfer reference, etc.) to settle, giving later
-- reconciliation something concrete to check instead of pure self-attestation.
ALTER TABLE trade_execution ADD COLUMN payment_reference VARCHAR(255);
