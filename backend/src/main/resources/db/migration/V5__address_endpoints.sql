-- Address endpoint addressbook: named entries for wallet and contract addresses,
-- scoped either to the registry operator (owner_type='OPERATOR', owner_id IS NULL)
-- or to a specific legal entity (owner_type='ENTITY', owner_id=entity UUID).

CREATE TABLE address_endpoint (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(10)  NOT NULL,          -- 'OPERATOR' | 'ENTITY'
    owner_id     UUID,                            -- NULL for operator scope
    address      VARCHAR(66)  NOT NULL,
    address_type VARCHAR(10)  NOT NULL,          -- 'WALLET' | 'CONTRACT'
    name         VARCHAR(200) NOT NULL,
    notes        VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial unique indexes handle the NULL owner_id case correctly
CREATE UNIQUE INDEX idx_endpoint_entity_address
    ON address_endpoint (owner_type, owner_id, address)
    WHERE owner_id IS NOT NULL;

CREATE UNIQUE INDEX idx_endpoint_operator_address
    ON address_endpoint (owner_type, address)
    WHERE owner_id IS NULL;

CREATE INDEX idx_endpoint_owner ON address_endpoint (owner_type, owner_id);
CREATE INDEX idx_endpoint_address ON address_endpoint (address);
