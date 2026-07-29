-- Finding #8, Phase 8: micar_authorization/emt_flag/redemption_at_par were operator-entered
-- free text with nothing distinguishing "disclosed" from "actually checked against a real
-- register" — the UI could be misread as a verified issuer-authorization check. These columns
-- make that distinction explicit; they are not themselves a live register cross-check.
ALTER TABLE payment_rail
    ADD COLUMN micar_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN micar_verified_at TIMESTAMPTZ,
    ADD COLUMN micar_verified_by UUID;
