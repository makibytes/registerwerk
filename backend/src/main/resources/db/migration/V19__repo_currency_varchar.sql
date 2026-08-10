-- Hibernate maps @Column(length=3) String as VARCHAR. PostgreSQL CHAR(3) pads values and is a
-- different JDBC type, so keep ISO currency validation while matching the entity mapping.
ALTER TABLE repo_rfq ALTER COLUMN cash_currency TYPE VARCHAR(3);
ALTER TABLE repo_trade ALTER COLUMN cash_currency TYPE VARCHAR(3);

