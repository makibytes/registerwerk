-- K10 / 6-26..6-29: Travel Rule outbound payload + delivery state, Art. 14(5) wallet-control proofs,
-- CASP register identifiers (LEI) and reviewed third-country rows, authenticated inbound peers.

-- ── travel_rule_message: delivery bookkeeping, valuation, inbound matching ────────────────────
ALTER TABLE travel_rule_message
    ADD COLUMN attempts            INT          NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at       TIMESTAMPTZ,
    ADD COLUMN valuation_source    TEXT,
    ADD COLUMN valuation_at        TIMESTAMPTZ,
    ADD COLUMN wallet_proof_id     UUID,
    ADD COLUMN payload_hash        VARCHAR(64),
    ADD COLUMN transfer_details    JSONB,
    ADD COLUMN matched_transfer_id UUID,
    ADD COLUMN matched_at          TIMESTAMPTZ,
    ADD COLUMN peer_vasp_id        TEXT;

CREATE INDEX idx_trm_open ON travel_rule_message (status, updated_at)
    WHERE status IN ('PENDING_SEND','FAILED','INCOMPLETE','CONFLICT','UNHOSTED_VERIFY_REQUIRED','INCOMPLETE_IVMS');
CREATE INDEX idx_trm_retry ON travel_rule_message (next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
CREATE INDEX idx_trm_inbound_unmatched ON travel_rule_message (created_at)
    WHERE direction = 'INBOUND' AND matched_transfer_id IS NULL;

-- Inbound dedup: (authenticated peer, protocol message id, payload hash). Same hash = idempotent
-- re-delivery; a different hash under the same reference is stored as CONFLICT instead of dropped.
DROP INDEX IF EXISTS uq_trm_inbound_vasp_transfer_ref;
CREATE UNIQUE INDEX uq_trm_inbound_peer_ref_hash
    ON travel_rule_message (originator_vasp_did, protocol_message_id, payload_hash)
    WHERE direction = 'INBOUND' AND protocol_message_id IS NOT NULL;

-- ── CASP register ───────────────────────────────────────────────────────────────────────────────
ALTER TABLE casp_authorization ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE casp_authorization
    ADD COLUMN reviewed_by          UUID,
    ADD COLUMN second_approver_id   UUID,
    ADD COLUMN country              VARCHAR(2);

UPDATE casp_authorization SET lei = upper(btrim(lei)) WHERE lei IS NOT NULL;
UPDATE casp_authorization SET lei = NULL WHERE lei = '';
-- Keep the most recently updated row of any duplicated LEI; record the cleared identifier in notes.
UPDATE casp_authorization c
   SET notes = btrim(coalesce(notes, '') || ' [V42: duplicate LEI ' || c.lei || ' cleared]'),
       lei = NULL
 WHERE c.lei IS NOT NULL
   AND EXISTS (SELECT 1 FROM casp_authorization o
                WHERE o.lei = c.lei AND o.id <> c.id
                  AND (o.updated_at, o.id) > (c.updated_at, c.id));
CREATE UNIQUE INDEX uq_casp_authorization_lei ON casp_authorization (lei) WHERE lei IS NOT NULL;

CREATE TABLE casp_register_import (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source         TEXT        NOT NULL,
    created        INT         NOT NULL,
    updated        INT         NOT NULL,
    status_changed INT         NOT NULL,
    failed         INT         NOT NULL,
    diff_digest    VARCHAR(64) NOT NULL,
    actor_id       UUID,
    approver_id    UUID,
    imported_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Art. 14(5) TFR: proof that a registered holder wallet is controlled by the holder ──────────
CREATE TABLE wallet_control_proof (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id          UUID         NOT NULL REFERENCES legal_entity(id),
    wallet_address           VARCHAR(66)  NOT NULL,
    chain_config_id          UUID,
    method                   VARCHAR(30)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    nonce                    VARCHAR(64),
    challenge_message        TEXT,
    signature                TEXT,
    evidence_ref             TEXT,
    verified_at              TIMESTAMPTZ,
    verified_by              UUID,
    dual_control_approver_id UUID,
    expires_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_wcp_method CHECK (method IN ('SIGNED_MESSAGE','OPERATOR_ATTESTATION')),
    CONSTRAINT chk_wcp_status CHECK (status IN ('PENDING','VERIFIED','REVOKED','EXPIRED'))
);
CREATE INDEX idx_wcp_lookup ON wallet_control_proof (lower(wallet_address), legal_entity_id) WHERE status = 'VERIFIED';

-- ── Authenticated inbound peers ──────────────────────────────────────────────────────────────────
CREATE TABLE travel_rule_peer (
    vasp_id            VARCHAR(255) PRIMARY KEY,
    legal_name         TEXT,
    lei                VARCHAR(20),
    hmac_key_ciphertext TEXT,
    key_kid            VARCHAR(64),
    cert_fingerprint   VARCHAR(64),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_by         UUID,
    second_approver_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_trp_peer_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_trp_peer_cred   CHECK (hmac_key_ciphertext IS NOT NULL OR cert_fingerprint IS NOT NULL)
);

CREATE TABLE travel_rule_replay (
    vasp_id        VARCHAR(255) NOT NULL,
    signature_hash VARCHAR(64)  NOT NULL,
    seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (vasp_id, signature_hash)
);
CREATE INDEX idx_trp_replay_seen ON travel_rule_replay (seen_at);
