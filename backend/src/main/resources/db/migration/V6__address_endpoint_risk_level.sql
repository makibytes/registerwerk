ALTER TABLE address_endpoint ADD COLUMN risk_level VARCHAR(10);
ALTER TABLE address_endpoint ADD CONSTRAINT chk_risk_level
    CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH'));
