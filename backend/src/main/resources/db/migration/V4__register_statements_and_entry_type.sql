-- V4: Register statements (§19 eWpG), entry type (Einzel-/Sammeleintragung, §8 eWpG),
-- and consumer / single-entry holder attributes.
--
-- §19 eWpG obliges the registry operator to provide a CONSUMER holder of a
-- SINGLE-ENTRY (Einzeleintragung) crypto security with a register statement
-- (Registerauszug) in text form: after the initial entry in their favour, after
-- every change to the register content concerning them, and at least once a year.
-- The issued statements are themselves register records and must be retained and
-- auditable, hence a dedicated table rather than fire-and-forget e-mail.

-- ── Asset: entry type (Einzel- vs. Sammeleintragung, §8 eWpG) ─────────────────
ALTER TABLE asset
    ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE';
-- COLLECTIVE = Sammeleintragung (holder is a custodian/Verwahrer),
-- INDIVIDUAL = Einzeleintragung (holder is the investor, pseudonymised),
-- MIXED      = Mischbestand (both forms coexist for the same asset).
COMMENT ON COLUMN asset.entry_type IS
    'eWpG §8 Eintragungsart: COLLECTIVE (Sammel), INDIVIDUAL (Einzel), MIXED.';

-- ── Holder: consumer flag + pseudonymous identifier for single entry ──────────
ALTER TABLE asset_holder
    ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE',
    -- §17(2) eWpG: in a single entry the holder is designated by a unique
    -- pseudonymous identifier rather than by clear name on-chain.
    ADD COLUMN holder_reference VARCHAR(64),
    -- §19(2) eWpG only obliges statements toward CONSUMER holders; institutional
    -- custodians in a collective entry are excluded.
    ADD COLUMN is_consumer BOOLEAN NOT NULL DEFAULT false,
    -- §17(2) eWpG additional single-entry register content:
    ADD COLUMN third_party_rights TEXT,
    ADD COLUMN disposal_restrictions TEXT,
    ADD COLUMN legal_capacity_note TEXT,
    -- Tracks when the last §19 annual statement was issued, to drive the scheduler.
    ADD COLUMN last_statement_at TIMESTAMPTZ;

COMMENT ON COLUMN asset_holder.holder_reference IS
    'eWpG §17(2) pseudonymous unique identifier for single-entry (Einzeleintragung) holders.';

CREATE UNIQUE INDEX idx_asset_holder_reference
    ON asset_holder (asset_id, holder_reference)
    WHERE holder_reference IS NOT NULL;

-- ── Register statement records (§19 eWpG) ─────────────────────────────────────
CREATE TABLE register_statement (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_id        UUID NOT NULL REFERENCES asset_holder(id),
    asset_id         UUID NOT NULL REFERENCES asset(id),
    investor_id      UUID NOT NULL REFERENCES legal_entity(id),
    -- Trigger: INITIAL_ENTRY, CHANGE, ANNUAL, ON_DEMAND (§19(1)).
    trigger          VARCHAR(20) NOT NULL,
    -- Snapshot of register content at issuance time (the statement is a record).
    nominal_amount   NUMERIC(38,18) NOT NULL,
    wallet_address   VARCHAR(66),
    holder_reference VARCHAR(64),
    content_hash     VARCHAR(66) NOT NULL,    -- keccak/sha-256 of the rendered PDF
    pdf_document_id  UUID,                    -- reference into the document store
    -- Delivery status of the text-form statement (§19: "in Textform").
    delivery_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivery_channel VARCHAR(20),
    delivery_error   TEXT,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX idx_register_statement_holder ON register_statement (holder_id);
CREATE INDEX idx_register_statement_investor ON register_statement (investor_id);
CREATE INDEX idx_register_statement_issued ON register_statement (issued_at);
CREATE INDEX idx_register_statement_delivery ON register_statement (delivery_status)
    WHERE delivery_status IN ('PENDING', 'FAILED');
