-- Stores the full T-REX suite contract addresses for each ERC-3643 token deployment.
-- One ERC-3643 deployment = Token + Identity Registry + Compliance + CTR + TIR.

CREATE TABLE erc3643_suite (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id        UUID        NOT NULL REFERENCES asset_deployment(id) UNIQUE,
    token_address              VARCHAR(66),
    identity_registry_address  VARCHAR(66),
    identity_registry_storage  VARCHAR(66),
    compliance_address         VARCHAR(66),
    claim_topics_registry      VARCHAR(66),
    trusted_issuers_registry   VARCHAR(66),
    factory_tx_hash            VARCHAR(66),
    is_confidential            BOOLEAN     NOT NULL DEFAULT false,  -- Zama fhEVM variant
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Compliance modules attached to a suite
CREATE TABLE erc3643_compliance_module (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id         UUID        NOT NULL REFERENCES erc3643_suite(id),
    module_address   VARCHAR(66) NOT NULL,
    module_type      VARCHAR(50) NOT NULL, -- 'MAX_BALANCE', 'MAX_INVESTORS', 'COUNTRY_RESTRICT', etc.
    parameters       JSONB,
    added_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at       TIMESTAMPTZ
);

-- Trusted claim issuers registered for a suite
CREATE TABLE erc3643_trusted_issuer (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id         UUID        NOT NULL REFERENCES erc3643_suite(id),
    issuer_address   VARCHAR(66) NOT NULL,
    claim_topics     BIGINT[]    NOT NULL DEFAULT '{}',
    added_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at       TIMESTAMPTZ
);

-- Claim topics required by a suite
CREATE TABLE erc3643_claim_topic (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id         UUID        NOT NULL REFERENCES erc3643_suite(id),
    topic            BIGINT      NOT NULL,
    label            VARCHAR(50),           -- e.g. 'KYC', 'AML', 'ACCREDITATION'
    UNIQUE (suite_id, topic)
);
