-- Review phase 2 veto B1: a voucher counts once per (policy, sender, UserOperation nonce).
-- Re-issuing for the same nonce replaces the earlier row (GasSponsorshipVoucherService), so
-- repeated requests can no longer book the same operation's worst-case prefund again and again
-- against the policy's monthly cap. Pre-existing duplicates keep only their newest row.
DELETE FROM gas_sponsorship_voucher v
USING gas_sponsorship_voucher newer
WHERE v.policy_id = newer.policy_id
  AND v.sender = newer.sender
  AND v.user_op_nonce = newer.user_op_nonce
  AND (v.created_at, v.id) < (newer.created_at, newer.id);

CREATE UNIQUE INDEX uq_gas_sponsorship_voucher_policy_sender_nonce
    ON gas_sponsorship_voucher (policy_id, sender, user_op_nonce);

CREATE INDEX idx_gas_sponsorship_voucher_policy_entity_created
    ON gas_sponsorship_voucher (policy_id, entity_id, created_at);
