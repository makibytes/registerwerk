-- T7-05: cloud-KMS custody. A KMS wallet is an opaque EVM key like a PKCS#11 one: no keystore, a mandatory
-- key_reference (the KMS key version resource name). Widens the V1 custody constraints and the unique
-- key-reference index; existing SOFTWARE / PKCS11 rows satisfy the new checks unchanged.
-- migration-safety: ack (CHECK constraint re-created widened in the next statement; no data is removed)
ALTER TABLE operator_wallet DROP CONSTRAINT chk_wallet_custody_type;
ALTER TABLE operator_wallet ADD CONSTRAINT chk_wallet_custody_type
    CHECK (custody_type IN ('SOFTWARE', 'PKCS11', 'KMS'));

-- migration-safety: ack (CHECK constraint re-created widened in the next statement; no data is removed)
ALTER TABLE operator_wallet DROP CONSTRAINT chk_wallet_custody_reference;
ALTER TABLE operator_wallet ADD CONSTRAINT chk_wallet_custody_reference CHECK (
    (custody_type = 'SOFTWARE' AND keystore_path IS NOT NULL AND key_reference IS NULL)
    OR
    (custody_type IN ('PKCS11', 'KMS') AND type = 'EVM' AND keystore_path IS NULL AND key_reference IS NOT NULL)
);

DROP INDEX uq_operator_wallet_pkcs11_reference;
CREATE UNIQUE INDEX uq_operator_wallet_opaque_reference
    ON operator_wallet (key_reference)
    WHERE custody_type IN ('PKCS11', 'KMS');
