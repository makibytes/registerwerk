ALTER TABLE operator_wallet
    ALTER COLUMN keystore_path DROP NOT NULL,
    ADD COLUMN custody_type VARCHAR(20) NOT NULL DEFAULT 'SOFTWARE',
    ADD COLUMN key_reference VARCHAR(255);

ALTER TABLE operator_wallet
    ADD CONSTRAINT chk_wallet_custody_type
        CHECK (custody_type IN ('SOFTWARE', 'PKCS11')),
    ADD CONSTRAINT chk_wallet_custody_reference
        CHECK (
            (custody_type = 'SOFTWARE' AND keystore_path IS NOT NULL AND key_reference IS NULL)
            OR
            (custody_type = 'PKCS11' AND type = 'EVM' AND keystore_path IS NULL AND key_reference IS NOT NULL)
        );

CREATE UNIQUE INDEX uq_operator_wallet_pkcs11_reference
    ON operator_wallet (key_reference)
    WHERE custody_type = 'PKCS11';
