-- MiFID II client classification, suitability assessment, and per-asset target market
-- (F-BLOCKER-11): previously no retail/professional/ECP flag existed anywhere, no suitability
-- questionnaire, and no target-market restriction on an asset — an EU bank could not onboard a
-- client to this at all.

ALTER TABLE legal_entity ADD COLUMN client_category VARCHAR(30);
ALTER TABLE legal_entity ADD COLUMN client_category_classified_at TIMESTAMPTZ;
ALTER TABLE legal_entity ADD COLUMN client_category_classified_by UUID;
ALTER TABLE legal_entity ADD CONSTRAINT chk_legal_entity_client_category CHECK (
    client_category IS NULL OR client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
);

CREATE TABLE suitability_assessment (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                     UUID NOT NULL REFERENCES legal_entity(id),
    knowledge_experience          VARCHAR(20) NOT NULL,
    risk_tolerance                VARCHAR(20) NOT NULL,
    investment_horizon_years      INTEGER,
    financial_situation_adequate  BOOLEAN NOT NULL,
    notes                         TEXT,
    assessed_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    assessed_by                   UUID,
    CONSTRAINT chk_suitability_knowledge CHECK (knowledge_experience IN ('NONE', 'BASIC', 'ADVANCED')),
    CONSTRAINT chk_suitability_risk CHECK (risk_tolerance IN ('LOW', 'MEDIUM', 'HIGH'))
);
CREATE INDEX idx_suitability_assessment_entity ON suitability_assessment (entity_id, assessed_at DESC);

ALTER TABLE asset ADD COLUMN target_market_min_experience VARCHAR(20);
ALTER TABLE asset ADD CONSTRAINT chk_asset_target_market_min_experience CHECK (
    target_market_min_experience IS NULL OR target_market_min_experience IN ('NONE', 'BASIC', 'ADVANCED')
);

CREATE TABLE asset_target_market_category (
    asset_id         UUID NOT NULL REFERENCES asset(id),
    client_category  VARCHAR(30) NOT NULL,
    PRIMARY KEY (asset_id, client_category),
    CONSTRAINT chk_asset_target_market_category CHECK (
        client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
    )
);
