-- Registerwerk database schema — the single clean-install baseline.
--
-- Flyway applies this file once to an empty database; every later schema change is a new
-- V{n}__description.sql. Tables are written in their final shape (columns in the order they were introduced
-- while the schema was developed, which is not always a logical grouping); the indexes, triggers and functions
-- that belong to a table follow it, and the file is organised by functional area.
--
-- Comments elsewhere in the code base that cite V2..V58 (V13, V28, ...) refer to the development history this
-- baseline was squashed from; every object they name is defined in this file.
--
-- Database logins (the contract shared with the compose `db-roles` service, the Helm init container and the
-- Cloud SQL runbook, see postgres-init/roles/ensure-runtime-role.sh):
--   migrator  (e.g. "registerwerk")  owns every object below; used only by Flyway (spring.flyway.user).
--   runtime   "registerwerk_app"     the application datasource: DML only, no DDL, and no UPDATE/DELETE/TRUNCATE on
--                                    the append-only audit tables. It is created out of band BEFORE Flyway as a
--                                    LOGIN role; this file only creates it (NOLOGIN) when it does not exist yet
--                                    and grants it its privileges in the last section. Without the split the
--                                    audit REVOKEs would be no-ops (an owner can lift any privilege or trigger);
--                                    AuditReadinessCheck fails production startup if the runtime login owns audit_event.

-- ── Database roles ───────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk_app') THEN
        CREATE ROLE registerwerk_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk_audit_reader') THEN
        CREATE ROLE registerwerk_audit_reader NOLOGIN;
    END IF;
END
$$;

-- The default migrator login becomes a member of the runtime role (so it can administer the grants below).
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'registerwerk') THEN
        GRANT registerwerk_app TO registerwerk;
    END IF;
END
$$;

-- ── Audit sequence (referenced by audit_event DEFAULT) ───────────────────────
CREATE SEQUENCE IF NOT EXISTS audit_event_seq START 1 INCREMENT 1;

-- ═══════════════════════════════════════════════════════════════════════════
-- LEGAL ENTITIES & KYC
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE legal_entity (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_number        VARCHAR(30) NOT NULL UNIQUE,
    type                 VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING_ONBOARDING',
    current_name         VARCHAR(500) NOT NULL,
    lei_code             VARCHAR(20),
    registration_number  VARCHAR(100),
    registration_country VARCHAR(2),
    incorporation_date   DATE,
    kyc_status           VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    kyc_expiry_date      DATE,
    idp_issuer_url       VARCHAR(500),
    idp_client_id        VARCHAR(255),
    -- Retained but never written. Inbound B2B federation is configured tenant-to-tenant in the
    -- Entra portal; Registerwerk never runs an authorization-code flow against a customer's
    -- tenant, so it has no use for their client secret and must not hold one in plaintext.
    idp_client_secret    VARCHAR(500),
    -- Per-legal-entity identity model: whether the operator invites this customer's users as B2B
    -- guests into its own tenant (and therefore manages their MFA), or federates to the
    -- customer's own Entra tenant (and therefore does not). This records operator *intent*;
    -- once a user has actually signed in, app_user.entra_tenant_id is the ground truth.
    identity_model       VARCHAR(20) NOT NULL DEFAULT 'WORKFORCE_GUEST',
    idp_tenant_id        UUID,
    -- Whether MFA performed in the customer's home tenant is trusted here (Entra cross-tenant
    -- access settings). Operator-controlled only: a customer self-asserting "trust my MFA"
    -- would be a privilege-escalation vector.
    idp_mfa_trusted      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    client_category VARCHAR(30),
    client_category_classified_at TIMESTAMPTZ,
    client_category_classified_by UUID,
    assigned_relationship_manager_id UUID,
    CONSTRAINT chk_entity_type   CHECK (type IN ('ISSUER','INVESTOR','AUDITOR')),
    CONSTRAINT chk_kyc_status    CHECK (kyc_status IN ('NOT_STARTED','IN_PROGRESS','APPROVED','REJECTED','EXPIRED')),
    CONSTRAINT chk_legal_entity_identity_model
        CHECK (identity_model IN ('WORKFORCE_MEMBER', 'WORKFORCE_GUEST', 'FEDERATED')),
    CONSTRAINT chk_legal_entity_client_category CHECK (
        client_category IS NULL OR client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
    ),
    CONSTRAINT chk_entity_status
        CHECK (status IN ('PENDING_ONBOARDING','ACTIVE','SUSPENDED','DISSOLVED','CLOSED','PENDING_REACTIVATION'))
);
CREATE INDEX idx_legal_entity_type   ON legal_entity (type);
CREATE INDEX idx_legal_entity_status ON legal_entity (status);
CREATE INDEX idx_legal_entity_lei    ON legal_entity (lei_code) WHERE lei_code IS NOT NULL;
CREATE INDEX idx_legal_entity_assigned_rm ON legal_entity (assigned_relationship_manager_id);

CREATE TABLE entity_name_history (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID NOT NULL REFERENCES legal_entity(id),
    previous_name     VARCHAR(500) NOT NULL,
    new_name          VARCHAR(500) NOT NULL,
    change_type       VARCHAR(30) NOT NULL,
    related_entity_id UUID REFERENCES legal_entity(id),
    effective_date    DATE NOT NULL,
    notes             TEXT,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by       UUID,
    CONSTRAINT chk_name_change_type CHECK (
        change_type IN ('RENAME','MERGER_ABSORBED','MERGER_SURVIVOR','ACQUISITION')
    )
);
CREATE INDEX idx_name_history_entity ON entity_name_history (legal_entity_id);

CREATE TABLE entity_merge_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    merge_type       VARCHAR(20) NOT NULL DEFAULT 'ABSORPTION',
    effective_date   DATE NOT NULL,
    notes            TEXT,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by      UUID,
    CONSTRAINT chk_merge_type    CHECK (merge_type IN ('ABSORPTION','CONSOLIDATION')),
    CONSTRAINT chk_no_self_merge CHECK (source_entity_id != target_entity_id)
);
CREATE INDEX idx_merge_source ON entity_merge_record (source_entity_id);
CREATE INDEX idx_merge_target ON entity_merge_record (target_entity_id);

-- Phase 6 K8 (6-21..6-24, DSAR part of 6-30): operator work items raised by the entity lifecycle, and the
-- COMPLETED_PARTIAL outcome of a DSAR erasure.
--
-- entity_task: one OPEN row per (entity, kind, ref). Kinds written by the code today:
--   ISSUER_ASSET_LIVE, SPERRVERMERK_HOLDING, REPO_TRADE_OPEN, LENDING_POSITION_OPEN, TRADE_OPEN,
--   CORPORATE_ACTION_PENDING, PORTFOLIO_MIGRATION_OPEN (offboarding follow-ups, alerting until DONE),
--   KYC_REVIEW_REQUIRED (name/LEI/country change, merger target), CHAIN_REINSTATEMENT_REQUIRED (reactivation).
-- Nothing here deletes or purges anything; retention periods and sweeps are a parked legal decision (T6-10).
CREATE TABLE entity_task (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID         NOT NULL REFERENCES legal_entity(id),
    kind         VARCHAR(48)  NOT NULL,
    ref_id       VARCHAR(128) NOT NULL DEFAULT '',
    detail       TEXT,
    status       VARCHAR(8)   NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    done_at      TIMESTAMPTZ,
    done_by      UUID,
    done_note    TEXT,
    CONSTRAINT chk_entity_task_status CHECK (status IN ('OPEN', 'DONE'))
);

-- A repeated delivery of the same trigger must not stack tasks.
CREATE UNIQUE INDEX uq_entity_task_open ON entity_task (entity_id, kind, ref_id) WHERE status = 'OPEN';
CREATE INDEX idx_entity_task_open ON entity_task (created_at) WHERE status = 'OPEN';
CREATE INDEX idx_entity_task_entity ON entity_task (entity_id, status);

CREATE TABLE kyc_document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    document_type   VARCHAR(64) NOT NULL,
    jurisdiction    VARCHAR(20),
    mime_type       VARCHAR(100) NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    storage_ref     VARCHAR(1000) NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_by     UUID,
    expires_at      DATE,
    deleted_at      TIMESTAMPTZ,
    -- Phase 6 K6 (6-07, 6-15, 6-16, 6-17): KYC/CDD gate controls.
    --
    -- kyc_document.issue_date: captured at upload next to expires_at so expiry and "too old" are real checks
    --   (expires_at had no writer outside the demo seeder; the checklist never saw an expired upload).
    -- beneficial_owner: cease needs a reason (and optionally a register extract), a verification trail
    --   (verification_document_id with the existing verified_by/verified_at) and the documented reason of a
    --   senior-managing-official fallback (control_type SENIOR_MANAGING_OFFICIAL; parked decision T6-01).
    -- edd_approval: enhanced-due-diligence approval of a confirmed PEP; four-eyes (approved_by + second_approver_id),
    --   review_due is capped at 6 months by the service (parked decision T6-02). A confirmed PEP passes the
    --   screening gate only while an unexpired row covers it.
    -- kyc_approval_record: evidence snapshot of every entity-level KYC approval (checklist outcome, override note,
    --   BO coverage, expiry, second approver) - the audit event carries the same payload, this row is queryable.
    issue_date DATE,
    CONSTRAINT chk_mime CHECK (
        mime_type IN (
            'application/pdf','image/jpeg','image/png',
            'image/tiff','application/xml','text/xml',
            'application/octet-stream'
        )
    )
);
CREATE INDEX idx_kyc_doc_entity ON kyc_document (legal_entity_id);
CREATE INDEX idx_kyc_doc_type   ON kyc_document (legal_entity_id, document_type);

CREATE TABLE kyc_document_content (
    id      UUID PRIMARY KEY REFERENCES kyc_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);

CREATE TABLE kyc_jurisdiction_approval (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID NOT NULL REFERENCES legal_entity(id),
    jurisdiction     VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by      UUID,
    approved_at      TIMESTAMPTZ,
    expires_at       DATE,
    rejection_reason TEXT,
    override_note    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, jurisdiction)
);
CREATE INDEX idx_kyc_jur_entity ON kyc_jurisdiction_approval(entity_id);

CREATE TABLE onboarding_token (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    token_hash      VARCHAR(128) NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    issued_by       UUID
);
CREATE UNIQUE INDEX idx_onboarding_token_active
    ON onboarding_token (legal_entity_id) WHERE used_at IS NULL;
CREATE INDEX idx_onboarding_token_hash ON onboarding_token (token_hash);

-- ═══════════════════════════════════════════════════════════════════════════
-- APP USERS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE app_user (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(320) NOT NULL UNIQUE,
    password_hash    VARCHAR(100),
    full_name        VARCHAR(200),
    role             VARCHAR(30)  NOT NULL DEFAULT 'REGISTRY_ADMIN',
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    legal_entity_id  UUID REFERENCES legal_entity(id),
    auth_provider    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at    TIMESTAMPTZ,
    created_by       UUID,
    -- TOTP step-up MFA (RFC 6238)
    totp_secret      TEXT,
    totp_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_enrolled_at TIMESTAMPTZ,
    -- Microsoft Entra ID identity mapping. entra_object_id is the token's `oid`: stable per user
    -- per tenant, and the join key between an Entra principal and this row. Without it app_user.id
    -- and the token's `sub` would be unrelated values, so SecurityUtils.extractUserId() would
    -- return an id matching no row — breaking step-up issuance, dual-control self-approval checks
    -- and every audit_event actor_id. NULL for LOCAL accounts.
    entra_object_id  UUID,
    -- Home tenant of the principal (token `tid`). When it differs from our own tenant the user is
    -- federated from a customer's tenant, and we can neither read nor manage their MFA methods.
    entra_tenant_id  UUID,
    -- Advisory cache of the Microsoft Graph second-factor lookup, so the nav banner does not cost
    -- a Graph round-trip on every page load. NEVER an authorisation input: Conditional Access is
    -- the enforcement point, and a stale cache must not be able to grant or deny access.
    entra_mfa_registered_at TIMESTAMPTZ,
    entra_mfa_checked_at    TIMESTAMPTZ,
    external_subject VARCHAR(255),
    -- Tokens issued before this instant are rejected (iat < tokens_valid_after). Bumped by AppUser setters
    -- on disable/enable, role, entity or password change and by SessionRevocationPort.revokeAll.
    tokens_valid_after TIMESTAMPTZ,
    -- Who last changed this account's roles (reviewer independence in access review) and when
    -- (campaign-completeness check at close). Creation counts through created_at.
    roles_changed_by UUID,
    roles_changed_at TIMESTAMPTZ,
    -- Set on the account DefaultAdminSeeder creates; cleared when the password is changed through a
    -- reset token. ProductionReadinessCheck fails production boots that still carry it after 24 h.
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    -- ── 6-09: step-up MFA state shared across replicas, encrypted TOTP secret ──────────────────────
    -- totp_secret now holds an envelope-encrypted value ("enc:v1:..."); legacy plaintext rows are
    -- re-encrypted by TotpSecretMigration at startup and lazily on the next successful verification.
    totp_secret_kid VARCHAR(200),
    CONSTRAINT chk_app_user_auth_provider CHECK (auth_provider IN ('LOCAL','ENTRA','OIDC')),
    CONSTRAINT chk_app_user_role CHECK (
        role IN (
            'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
            'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER','SUPPORT_AGENT'
        )
    )
);
CREATE INDEX idx_app_user_email_lower     ON app_user (LOWER(email));
CREATE INDEX idx_app_user_legal_entity_id ON app_user (legal_entity_id);
COMMENT ON COLUMN app_user.entra_object_id IS
    'Entra object id (token `oid`). Stable per user per tenant; the join key between an Entra '
    'principal and this row. NULL for LOCAL accounts.';

-- Partial unique index rather than a UNIQUE constraint: every LOCAL account leaves this NULL,
-- and Postgres treats NULLs as distinct, but a partial index states the intent explicitly and
-- keeps the index off the many LOCAL rows.
CREATE UNIQUE INDEX ux_app_user_entra_object_id
    ON app_user (entra_object_id)
    WHERE entra_object_id IS NOT NULL;
COMMENT ON COLUMN app_user.external_subject IS
    'OIDC `sub` claim for a principal resolved via a non-Entra OIDC issuer (JWT_ISSUER_URI). '
    'NULL for LOCAL and ENTRA accounts, which are keyed by id / entra_object_id respectively.';
CREATE UNIQUE INDEX ux_app_user_external_subject
    ON app_user (external_subject)
    WHERE external_subject IS NOT NULL;
COMMENT ON COLUMN app_user.totp_secret_kid IS
    'Name of the KekProvider that wrapped the data key of totp_secret (rotation / audit aid).';

CREATE TABLE app_user_role (
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role        VARCHAR(30) NOT NULL,
    PRIMARY KEY (app_user_id, role),
    CONSTRAINT chk_app_user_role_entry CHECK (
        role IN (
            'REGISTRY_ADMIN','AUDIT','COMPLIANCE_OFFICER','RELATIONSHIP_MANAGER',
            'ISSUER','INVESTOR','COMPANY_ADMIN','TRADER','DAPP_PUBLISHER','SUPPORT_AGENT'
        )
    )
);

CREATE TABLE app_user_action_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(128) NOT NULL,
    token_type  VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_by  UUID,
    CONSTRAINT chk_app_user_action_token_type CHECK (
        token_type IN ('REGISTRATION','PASSWORD_RESET')
    )
);
CREATE UNIQUE INDEX idx_app_user_action_token_hash   ON app_user_action_token (token_hash);
CREATE INDEX        idx_app_user_action_token_active
    ON app_user_action_token (app_user_id, token_type) WHERE consumed_at IS NULL;

-- Individually revoked session tokens (logout, ended impersonation). Pruned once the token has expired.
CREATE TABLE session_revocation (
    jti        VARCHAR(64)  PRIMARY KEY,
    user_id    UUID,
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(40)
);
CREATE INDEX idx_session_revocation_expires ON session_revocation (expires_at);

-- One row per operator impersonation of a customer entity; id is the session token's jti.
CREATE TABLE impersonation_session (
    id                  UUID PRIMARY KEY,
    actor_id            UUID         NOT NULL,
    target_entity_id    UUID         NOT NULL,
    mode                VARCHAR(20)  NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    ticket_ref          VARCHAR(100),
    approver_id         UUID,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    ended_at            TIMESTAMPTZ,
    ended_by            UUID,
    end_reason          VARCHAR(40),
    handoff_code_hash   VARCHAR(64)  NOT NULL,
    handoff_expires_at  TIMESTAMPTZ  NOT NULL,
    handoff_consumed_at TIMESTAMPTZ,
    CONSTRAINT chk_impersonation_mode CHECK (mode IN ('READ_ONLY', 'ACT_ON_BEHALF')),
    CONSTRAINT uq_impersonation_handoff UNIQUE (handoff_code_hash)
);
CREATE INDEX idx_impersonation_session_entity ON impersonation_session (target_entity_id, started_at DESC);
CREATE INDEX idx_impersonation_session_open ON impersonation_session (expires_at) WHERE ended_at IS NULL;

-- Phase 6 / K3: step-up MFA and login hardening (findings 6-08, 6-09, 6-10).

-- ── 6-08: single-use dual-control approver tokens ──────────────────────────────────────────────
-- One row per consumed approver token (jti). The primary key makes "use once" an atomic database
-- fact shared by every replica: the second insert of the same jti is a unique violation.
CREATE TABLE dual_control_token_use (
    jti            VARCHAR(64)  PRIMARY KEY,
    approver_id    UUID         NOT NULL,
    initiator_id   UUID         NOT NULL,
    action         VARCHAR(200) NOT NULL,
    target_digest  VARCHAR(64),
    used_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_dual_control_token_use_expires ON dual_control_token_use (expires_at);
COMMENT ON TABLE dual_control_token_use IS
    'Consumed dual-control approver tokens (K3, 6-08). A token is bound to action + target and may '
    'be redeemed once; rows are pruned after expires_at (DualControlTokenUseHousekeeping).';

CREATE TABLE totp_state (
    user_id             UUID        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    last_accepted_step  BIGINT      NOT NULL DEFAULT 0,
    failed_attempts     INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE totp_state IS
    'RFC 6238 replay guard (last accepted time step) and brute-force lockout of the TOTP step-up, '
    'kept in the database so every replica enforces the same state (K3, 6-09).';

-- Wave 0a C3: the "fewer than two enrolled administrators" bootstrap exception of the four-eyes rule is a
-- ONE-WAY door. Without a persisted fact, disabling a colleague (one step-up) dropped the count of enabled,
-- TOTP-enrolled REGISTRY_ADMINs back below two and re-opened the single-step-up path (sock-puppet admin).
CREATE TABLE dual_control_bootstrap (
    id           BOOLEAN     NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    completed_at TIMESTAMPTZ
);
COMMENT ON TABLE dual_control_bootstrap IS
    'Singleton, write-once. completed_at is set the first time two enabled, TOTP-enrolled REGISTRY_ADMINs '
    'existed at once; from then on DualControlService.requireIfNotBootstrap never falls back to a single step-up.';

INSERT INTO dual_control_bootstrap (id) VALUES (TRUE);

-- completed_at can be set once and never cleared; the row cannot be deleted or the table truncated.
CREATE FUNCTION dual_control_bootstrap_one_way() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'dual_control_bootstrap is one-way: the row cannot be removed. Operation: %', TG_OP;
    END IF;
    IF OLD.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'dual_control_bootstrap is one-way: the bootstrap exception cannot be re-opened. Operation: %', TG_OP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_dual_control_bootstrap_one_way
    BEFORE UPDATE OR DELETE ON dual_control_bootstrap
    FOR EACH ROW EXECUTE FUNCTION dual_control_bootstrap_one_way();

CREATE TRIGGER trg_dual_control_bootstrap_no_truncate
    BEFORE TRUNCATE ON dual_control_bootstrap
    FOR EACH STATEMENT EXECUTE FUNCTION dual_control_bootstrap_one_way();

-- Closes the latch when two enabled, enrolled REGISTRY_ADMINs exist; returns whether it is closed.
-- The single definition of "enrolled administrator" for both the triggers and the application.
CREATE FUNCTION dual_control_bootstrap_observe() RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    closed BOOLEAN;
BEGIN
    SELECT completed_at IS NOT NULL INTO closed FROM dual_control_bootstrap WHERE id;
    IF closed THEN
        RETURN TRUE;
    END IF;
    IF (SELECT count(*) FROM app_user u
         WHERE u.enabled AND u.totp_enabled
           AND EXISTS (SELECT 1 FROM app_user_role r WHERE r.app_user_id = u.id AND r.role = 'REGISTRY_ADMIN')) >= 2 THEN
        UPDATE dual_control_bootstrap SET completed_at = now() WHERE id AND completed_at IS NULL;
        RETURN TRUE;
    END IF;
    RETURN FALSE;
END;
$$;

-- The latch must see the moment two such administrators exist, however they came to be (the operator
-- API, an IdP provisioning path, a seeder, SQL), not only when a privileged action next asks.
CREATE FUNCTION dual_control_bootstrap_observe_trg() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM dual_control_bootstrap_observe();
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_app_user_dual_control_bootstrap
    AFTER INSERT OR UPDATE OF enabled, totp_enabled ON app_user
    FOR EACH ROW WHEN (NEW.enabled AND NEW.totp_enabled)
    EXECUTE FUNCTION dual_control_bootstrap_observe_trg();

CREATE TRIGGER trg_app_user_role_dual_control_bootstrap
    AFTER INSERT ON app_user_role
    FOR EACH ROW WHEN (NEW.role = 'REGISTRY_ADMIN')
    EXECUTE FUNCTION dual_control_bootstrap_observe_trg();

-- T8-02 / Wave 3a-1: the in-app approval queue. An initiator files one concrete request (action, method, path,
-- query, canonical body); an eligible approver (REGISTRY_ADMIN / COMPLIANCE_OFFICER, never the requester) decides
-- it in the app; the requester then claims a server-minted, single-use dual-control approver token that is bound
-- to the exact request digest (the same digest StepUpTokenValidator checks on the real endpoint).
--
-- The state machine is enforced by atomic conditional UPDATEs in ApprovalRequestRepository; the constraints below
-- are the last line of defence if code ever gets it wrong.
CREATE TABLE approval_request (
    id                UUID          PRIMARY KEY,
    requester_user_id UUID          NOT NULL,
    action            VARCHAR(160)  NOT NULL,
    method            VARCHAR(8)    NOT NULL,
    path              VARCHAR(500)  NOT NULL,
    query             VARCHAR(2000),
    -- Canonical JSON of the request body (DualControlTarget.canonicalJson); NULL for reasons whose body is not
    -- bound (secret-bearing or non-JSON payloads are never copied into an approval request).
    canonical_body    TEXT,
    target_digest     VARCHAR(64)   NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    approver_user_id  UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ   NOT NULL,
    decided_at        TIMESTAMPTZ,
    decision_note     VARCHAR(1000),
    claimed_at        TIMESTAMPTZ,
    claim_jti         VARCHAR(64),
    CONSTRAINT approval_request_status_chk
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'CLAIMED')),
    -- Self-approval is impossible at the storage level, whatever the application does.
    CONSTRAINT approval_request_no_self_approval_chk
        CHECK (approver_user_id IS NULL OR approver_user_id <> requester_user_id),
    CONSTRAINT approval_request_decided_chk
        CHECK (status = 'PENDING' OR status IN ('EXPIRED', 'CANCELLED') OR approver_user_id IS NOT NULL),
    CONSTRAINT approval_request_claimed_chk
        CHECK ((status = 'CLAIMED') = (claim_jti IS NOT NULL AND claimed_at IS NOT NULL))
);
COMMENT ON TABLE approval_request IS
    'In-app dual-control approval queue (T8-02). One row = one concrete request; the approver token is minted only '
    'when the requester claims an APPROVED row, and is bound to target_digest.';
CREATE INDEX approval_request_pending_idx ON approval_request (created_at) WHERE status = 'PENDING';
CREATE INDEX approval_request_requester_idx ON approval_request (requester_user_id, created_at DESC);
CREATE INDEX approval_request_expiry_idx ON approval_request (expires_at) WHERE status IN ('PENDING', 'APPROVED');

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN REGISTRY
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE chain_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier          VARCHAR(50)  NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    chain_type          VARCHAR(10)  NOT NULL,
    network_type        VARCHAR(10)  NOT NULL,
    chain_id            BIGINT,
    rpc_url             VARCHAR(500) NOT NULL,
    ws_url              VARCHAR(500),
    fallback_rpc_urls   TEXT,
    block_explorer_url  VARCHAR(200),
    graph_node_url      VARCHAR(300),
    graph_subgraph_name VARCHAR(100),
    enabled             BOOLEAN      NOT NULL DEFAULT true,
    -- Canton-specific
    application_id      VARCHAR(255),
    synchronizer_id     VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Per-chain finality model: how a transaction/block on a chain becomes irreversible.
    -- DEPTH_BASED preserves the confirmation-depth-only behavior this registry always had, so
    -- existing rows are unaffected. TAG_BASED lets an EVM chain with real safe/finalized block tags
    -- (Ethereum L1, OP-Stack L2s) be confirmed against its actual finality guarantee instead of a
    -- depth heuristic; INSTANT is for permissioned BFT chains (Besu/Quorum QBFT, IBFT) that cannot
    -- reorg, where the first-seen receipt is already final.
    finality_model VARCHAR(20) NOT NULL DEFAULT 'DEPTH_BASED',
    -- Feeds the finality gate's "estimated time until this level is reached" — null (the default)
    -- means "unknown", never a guessed number; populated per chain by an operator, not derived here.
    avg_block_seconds INT,
    -- finality_source lets a chain opt into chaincache's push-based, gap-free durable retraction
    -- stream instead of this registry's own poll-based safe/finalized-tag probing — see
    -- blockchain.internal.ChaincacheDurableStreamManager. Defaults to the existing self-probe
    -- behavior so every pre-existing chain is unaffected until an operator explicitly opts in.
    finality_source VARCHAR(20) NOT NULL DEFAULT 'RPC_SELF_PROBE',
    -- Per-chain EVM fee ceilings (Phase 4 K4a, P4B-2 / parked T4-02 interim).
    -- NULL = use the global default (registerwerk.blockchain.fee-cap.default-max-fee-gwei /
    -- default-max-tip-gwei). Values are in wei. EvmContractService refuses to sign a transaction whose
    -- maxFeePerGas / maxPriorityFeePerGas (or legacy gasPrice) exceeds the applicable cap.
    max_fee_per_gas_wei          NUMERIC(38,0) CHECK (max_fee_per_gas_wei IS NULL OR max_fee_per_gas_wei > 0),
    max_priority_fee_per_gas_wei NUMERIC(38,0) CHECK (max_priority_fee_per_gas_wei IS NULL OR max_priority_fee_per_gas_wei > 0),
    -- RPC node governance and trust (Phase 4 K5: P4C-1 governance half, P4B-8, P4C-6 interim / parked T4-08).
    --
    -- chain_config.genesis_hash: the pinned genesis block hash (EVM block 0 hash, Solana getGenesisHash).
    --   NULL until the first health check of a node whose eth_chainId matches the pinned chain_id has
    --   captured it (compare-and-set, never overwritten by a node afterwards). A node that later answers a
    --   different genesis is marked unhealthy with health_reason CHAIN_MISMATCH and is never routed to.
    -- chain_config.rpc_allowed_hosts: optional comma-separated host allow-list for node URLs of this chain
    --   (NULL / blank = any host). Enforced when a node is added or its URL changed.
    -- rpc_node.health_reason: why the node is currently not healthy (CHAIN_MISMATCH, IMPLAUSIBLE_HEIGHT,
    --   PROBE_FAILED, SYNCING, LAGGING, STALLED, RECOVERING); NULL while healthy. CHAIN_MISMATCH and
    --   IMPLAUSIBLE_HEIGHT are quarantine reasons: such a node is not even a last-resort routing choice.
    -- rpc_node.consecutive_successes: hysteresis counter; an unhealthy node needs 2 consecutive good probes
    --   to be healthy again, but becomes unhealthy on the first failed probe.
    genesis_hash       VARCHAR(80),
    rpc_allowed_hosts  TEXT,
    CONSTRAINT chk_chain_type   CHECK (chain_type   IN ('EVM','SOLANA','STARKNET','STELLAR','CANTON')),
    CONSTRAINT chk_network_type CHECK (network_type IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_finality_model CHECK (finality_model IN ('TAG_BASED', 'DEPTH_BASED', 'INSTANT')),
    CONSTRAINT ck_chain_config_finality_source
        CHECK (finality_source IN ('RPC_SELF_PROBE', 'CHAINCACHE'))
);
CREATE INDEX idx_chain_config_type    ON chain_config (chain_type, network_type);
CREATE INDEX idx_chain_config_enabled ON chain_config (enabled);

CREATE TABLE rpc_node (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chain_config_id        UUID         NOT NULL REFERENCES chain_config(id) ON DELETE CASCADE,
    url                    VARCHAR(512) NOT NULL,
    label                  VARCHAR(100),
    enabled                BOOLEAN      NOT NULL DEFAULT true,
    exclusive              BOOLEAN      NOT NULL DEFAULT false,
    latest_block_number    BIGINT,
    block_last_advanced_at TIMESTAMPTZ,
    last_checked_at        TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    healthy                BOOLEAN      NOT NULL DEFAULT false,
    consecutive_failures   INT          NOT NULL DEFAULT 0,
    lag_from_best          INT,
    syncing                BOOLEAN      NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Promotes chaincache to a first-class node kind rather than an indistinguishable direct-RPC URL
    -- (see docs/plan "Preliminary-state awareness across the portfolio", Track C / P9). A
    -- CHAINCACHE-kind rpc_node additionally records the base chaincache instance URL
    -- (management_url, used for the GET /api/capabilities probe and the durable-stream WebSocket) and
    -- which of chaincache's own multi-chain keys (remote_chain_key) this ChainConfig maps to, plus the
    -- capabilities chaincache last reported (capabilities, JSONB — refreshed on add and by a periodic
    -- probe, not authoritative between probes).
    kind VARCHAR(20) NOT NULL DEFAULT 'DIRECT_RPC',
    management_url VARCHAR(512),
    remote_chain_key VARCHAR(80),
    capabilities JSONB,
    -- Consecutive-failure counter for RpcNodeService#redetectAll's demotion hysteresis: a CHAINCACHE
    -- node now only falls back to DIRECT_RPC after several consecutive failed probes, not one transient
    -- blip.
    chaincache_probe_failures INT NOT NULL DEFAULT 0,
    health_reason          VARCHAR(40),
    consecutive_successes  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_rpc_node_kind
        CHECK (kind IN ('DIRECT_RPC', 'CHAINCACHE')),
    CONSTRAINT ck_rpc_node_chaincache_fields
        CHECK (kind <> 'CHAINCACHE' OR (management_url IS NOT NULL AND remote_chain_key IS NOT NULL))
);
CREATE INDEX idx_rpc_node_chain  ON rpc_node (chain_config_id);
CREATE INDEX idx_rpc_node_usable ON rpc_node (chain_config_id, enabled, healthy);
CREATE UNIQUE INDEX ux_rpc_node_chain_url ON rpc_node (chain_config_id, lower(url));

-- ═══════════════════════════════════════════════════════════════════════════
-- ASSETS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE asset (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_number     VARCHAR(30) NOT NULL UNIQUE,
    issuer_id        UUID NOT NULL REFERENCES legal_entity(id),
    name             VARCHAR(500) NOT NULL,
    isin             VARCHAR(12),
    token_standard   VARCHAR(30) NOT NULL,
    onchain_level    VARCHAR(10) NOT NULL DEFAULT 'NONE',
    jurisdiction     VARCHAR(20),
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    termsheet_doc_id UUID REFERENCES kyc_document(id),
    public_data      JSONB,
    -- Preferred deployment target
    chain            VARCHAR(20),
    network          VARCHAR(10),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_holder_sync_time TIMESTAMP WITH TIME ZONE,
    -- Optimistic-lock version. AssetLifecycleService's submit/approve/reject/issue/suspend/
    -- reactivate/redeem methods all do a read-modify-write on asset.status; without a version
    -- check two concurrent transitions can both pass their precondition and both commit
    -- silently. JPA's @Version turns a lost update into an optimistic-lock failure (HTTP 409) —
    -- mirrors the identical guard on asset_holder.version below.
    version          BIGINT NOT NULL DEFAULT 0,
    -- ── Asset: entry type (Einzel- vs. Sammeleintragung, §8 eWpG) ─────────────────
    entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE',
    -- Baseline economic terms on every asset, regardless of token standard — distinct from
    -- asset_bond_terms, which only exists for bond-standard assets and is entered separately.
    -- Without these, statements, valuations, tax reporting, and corporate actions have no
    -- amount or currency to work from for any non-bond asset.
    currency VARCHAR(3),
    issue_size NUMERIC(38, 8),
    denomination NUMERIC(38, 8),
    issue_date DATE,
    maturity_date DATE,
    target_market_min_experience VARCHAR(20),
    min_investment_amount NUMERIC(38, 8),
    max_holding_amount NUMERIC(38, 8),
    -- T2-18: pledging/escrowing units into a pool contract (lending market, DvP escrow, desk,
    -- facility) left a positive-balance wallet with no asset_holder row. HolderDataService refused to
    -- reconcile (UnmappedHolderIdentityException) and HolderSyncScheduler only logged a WARN on every
    -- run, so the register silently went stale and corporate-action snapshots read it anyway.
    --
    -- 1. The blocked-register state is persisted on the asset (operator banner + metric + alert).
    -- 2. A pool contract gets a chain-derived NOMINEE_POOL holder row (investor = the operator's
    --    legal entity), so the register reconciles again.
    -- 3. Entitlements snapshotted for a NOMINEE_POOL row are HELD_LOOK_THROUGH and excluded from
    --    settlement until the look-through question (PARK-T2-18) is decided.
    -- 4. A corporate action whose record-date snapshot is refused becomes SNAPSHOT_BLOCKED (visible,
    --    retried daily) instead of being snapshotted from a stale register.
    holder_sync_status VARCHAR(16) NOT NULL DEFAULT 'OK',
    holder_sync_blocked_reason TEXT,
    -- Comma-separated, lower-cased wallet addresses that have a finalized positive balance but no
    -- asset_holder row — what the operator must map (investor) or register (nominee pool).
    holder_sync_unmapped_wallets TEXT,
    last_successful_holder_sync_at TIMESTAMPTZ,
    -- T3-09: number of active, non-chain-derived register entries with a positive nominal on an asset
    -- that has deployments. Such rows are not backed by the chain (e.g. off-chain trade settlement,
    -- manual entries); the holder sync reports them instead of blocking. Written only by
    -- HolderSyncStatusPortImpl bulk updates, like the other holder_sync_* columns.
    holder_sync_offchain_rows INTEGER NOT NULL DEFAULT 0,
    -- T3-08 primary subscription: accept -> payment confirmed -> settled flow, allocation expiry.
    -- T3-13 issuer edits of register entries: change requests + an audit row per executed change.
    subscription_payment_window_bd INT NOT NULL DEFAULT 10,
    CONSTRAINT chk_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            -- securities-grade additions
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_onchain_level CHECK (onchain_level IN ('NONE','SIMPLE','CONTROL')),
    CONSTRAINT chk_asset_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_asset_network CHECK (network IS NULL OR network IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_asset_chain_network_pair CHECK (
        (chain IS NULL AND network IS NULL) OR (chain IS NOT NULL AND network IS NOT NULL)
    ),
    CONSTRAINT chk_asset_target_market_min_experience CHECK (
        target_market_min_experience IS NULL OR target_market_min_experience IN ('NONE', 'BASIC', 'ADVANCED')
    ),
    CONSTRAINT ck_asset_holder_sync_status
        CHECK (holder_sync_status IN ('OK', 'BLOCKED')),
    CONSTRAINT chk_asset_status CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED','REDEMPTION_PENDING',
                   'TRANSFER_PENDING','TRANSFERRED_OUT')
    )
);
CREATE UNIQUE INDEX idx_asset_isin          ON asset (isin) WHERE isin IS NOT NULL;
CREATE INDEX        idx_asset_issuer        ON asset (issuer_id);
CREATE INDEX        idx_asset_status        ON asset (status);
CREATE INDEX        idx_asset_public_data   ON asset USING GIN (public_data) WHERE public_data IS NOT NULL;
CREATE INDEX        idx_asset_last_holder_sync_time ON asset(last_holder_sync_time DESC NULLS LAST);
CREATE INDEX        idx_asset_chain_network ON asset (chain, network) WHERE chain IS NOT NULL;

-- COLLECTIVE = Sammeleintragung (holder is a custodian/Verwahrer),
-- INDIVIDUAL = Einzeleintragung (holder is the investor, pseudonymised),
-- MIXED      = Mischbestand (both forms coexist for the same asset).
COMMENT ON COLUMN asset.entry_type IS
    'eWpG §8 Eintragungsart: COLLECTIVE (Sammel), INDIVIDUAL (Einzel), MIXED.';
CREATE INDEX idx_asset_holder_sync_blocked ON asset (id) WHERE holder_sync_status = 'BLOCKED';

CREATE TABLE asset_document (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID         NOT NULL REFERENCES asset(id),
    document_type    VARCHAR(30)  NOT NULL DEFAULT 'TERM_SHEET',
    source           VARCHAR(30)  NOT NULL DEFAULT 'UPLOAD',
    mime_type        VARCHAR(100) NOT NULL,
    file_name        VARCHAR(500),
    storage_ref      VARCHAR(1000),
    content_hash     VARCHAR(66),
    size_bytes       BIGINT,
    chain            VARCHAR(20),
    network          VARCHAR(10),
    onchain_doc_name VARCHAR(66),
    onchain_uri      VARCHAR(2000),
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by      UUID,
    fetched_at       TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    -- Phase 6 K11 (6-34, parked T6-18): after issuance a term sheet is only replaced through an operator-approved
    -- amendment (step-up + second approver). The replaced version stays in the table (never deleted) and points at
    -- its successor; the public ISIN endpoint serves the deterministic current version, never a superseded one.
    superseded_by UUID REFERENCES asset_document(id)
);
CREATE INDEX idx_asset_doc_asset_type
    ON asset_document(asset_id, document_type) WHERE deleted_at IS NULL;
COMMENT ON COLUMN asset_document.superseded_by IS
    'Set when an operator-approved amendment replaced this document; the row is kept for the audit trail.';

CREATE TABLE asset_document_content (
    id      UUID PRIMARY KEY REFERENCES asset_document(id) ON DELETE CASCADE,
    content BYTEA NOT NULL
);

CREATE TABLE asset_deployment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           UUID NOT NULL REFERENCES asset(id),
    chain              VARCHAR(20) NOT NULL,
    network            VARCHAR(10) NOT NULL,
    contract_address   VARCHAR(66),
    deployed_at        TIMESTAMPTZ,
    deployed_by_tx     varchar(128),
    block_hash         VARCHAR(66),
    block_number       BIGINT,
    deployment_status  VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    -- Mirrors blockchain_transaction.chain_config_id: lets the reorg-retraction sweep
    -- (finality.internal.BlockFinalityServiceImpl#recordRetraction) find affected asset_deployment
    -- rows by (chainConfigId, blockNumber) instead of resolving chain/network enums at query time.
    -- Nullable: a deployment is not always bound to a chain_config row.
    chain_config_id UUID REFERENCES chain_config(id),
    -- Wave 0b C5: register amounts are RAW token base units (asset_holder.nominal_amount and token_transfer.amount are
    -- written unscaled by the indexer), but coupon / redemption maths, the subscription mint and trading all assume WHOLE
    -- units. Bond / fund tokens are therefore deployed with decimals = 0 and every register-unit flow fails closed
    -- (RegisterUnits) on a deployment that does not report exactly 0. The deployment row now records the decimals the
    -- token was deployed with; NULL means "unknown" and is refused like any other non-zero value.
    token_decimals INTEGER,
    CONSTRAINT chk_chain CHECK (
        chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_network    CHECK (network IN ('MAINNET','TESTNET')),
    CONSTRAINT chk_dep_status CHECK (deployment_status IN ('PENDING','CONFIRMED','FAILED'))
);
CREATE UNIQUE INDEX idx_deployment_address
    ON asset_deployment (chain, network, contract_address) WHERE contract_address IS NOT NULL;
CREATE INDEX idx_deployment_asset ON asset_deployment (asset_id);
CREATE INDEX idx_asset_deployment_chain_config_block
    ON asset_deployment (chain_config_id, block_number);
COMMENT ON COLUMN asset_deployment.token_decimals IS
    'Decimals of the deployed token (0 = whole-unit register token). NULL = unknown (refused by RegisterUnits).';

CREATE TABLE asset_holder (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id          UUID NOT NULL REFERENCES asset(id),
    investor_id       UUID NOT NULL REFERENCES legal_entity(id),
    -- 0x-prefixed (EVM/Starknet) addresses are normalized to lowercase at write time
    -- (EvmUtils.normalizeAddress), matching indexer.api.HolderDataService's convention, so that
    -- an indexer-persisted address and a UI-entered checksummed one match. Solana (base58) and
    -- Stellar (base32) addresses also live in this column and are case-SENSITIVE by
    -- construction — lowercasing those would corrupt them, so normalization is 0x-only.
    wallet_address    VARCHAR(66) NOT NULL,
    whitelisted       BOOLEAN NOT NULL DEFAULT false,
    whitelist_tx_hash VARCHAR(66),
    nominal_amount    NUMERIC(96,18) NOT NULL DEFAULT 0,
    acquisition_date  DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ── Holder: consumer flag + pseudonymous identifier for single entry ──────────
    entry_type VARCHAR(20) NOT NULL DEFAULT 'COLLECTIVE',
    -- §17(2) eWpG: in a single entry the holder is designated by a unique
    -- pseudonymous identifier rather than by clear name on-chain.
    holder_reference VARCHAR(64),
    -- §19(2) eWpG only obliges statements toward CONSUMER holders; institutional
    -- custodians in a collective entry are excluded.
    is_consumer BOOLEAN NOT NULL DEFAULT false,
    -- §17(2) eWpG additional single-entry register content:
    third_party_rights TEXT,
    disposal_restrictions TEXT,
    legal_capacity_note TEXT,
    -- Tracks when the last §19 annual statement was issued, to drive the scheduler.
    last_statement_at TIMESTAMPTZ,
    -- Optimistic-lock version: nominal_amount is the legally canonical register
    -- balance (eWpG §16) and is mutated by read-modify-write in several paths that
    -- can run concurrently (trade settlement, manual §24 corrections, indexer sync).
    -- JPA's @Version turns a lost update into an optimistic-lock failure (HTTP 409).
    version BIGINT NOT NULL DEFAULT 0,
    -- Soft-delete marker. Removal must never hard-delete the row: a §16 eWpG register entry
    -- disappearing entirely conflicts with retention/tamper-evidence obligations.
    removed_at TIMESTAMPTZ,
    -- Distinguishes an off-chain register entry (manually maintained, chain not authoritative for it)
    -- from an on-chain holder whose wallet has, on a later resync, vanished from the counted set
    -- entirely — the two are otherwise indistinguishable to HolderDataService.syncHoldersFromBlockchain,
    -- which needs the flag to zero the latter without ever touching the former. Set by application code
    -- in a follow-on change; every existing row defaults to false (conservatively "not chain-derived",
    -- i.e. left untouched) until that code starts marking rows true going forward.
    chain_derived BOOLEAN NOT NULL DEFAULT false,
    holder_kind VARCHAR(20) NOT NULL DEFAULT 'INVESTOR',
    CONSTRAINT ck_asset_holder_kind
        CHECK (holder_kind IN ('INVESTOR', 'NOMINEE_POOL'))
);
CREATE INDEX        idx_holder_investor ON asset_holder (investor_id);
CREATE INDEX        idx_holder_asset    ON asset_holder (asset_id);
COMMENT ON COLUMN asset_holder.removed_at IS
    'Soft-delete marker. NULL = still an active register entry. Non-null = the instant '
    'HolderService.removeHolder closed this holder out; the row is retained (never hard-deleted) '
    'for eWpG §16 retention/tamper-evidence. Compliance-facing reads should exclude removed rows '
    '(see AssetHolderRepository.findActive* methods); reconciliation/audit reads may still need them.';

-- Compliance-facing listings filter on this predicate constantly (findActiveByAssetId /
-- findActiveByInvestorId / existsActiveBy...); index it alongside the existing lookup columns.
CREATE INDEX idx_holder_asset_active    ON asset_holder (asset_id)    WHERE removed_at IS NULL;
CREATE INDEX idx_holder_investor_active ON asset_holder (investor_id) WHERE removed_at IS NULL;
COMMENT ON COLUMN asset_holder.holder_reference IS
    'eWpG §17(2) pseudonymous unique identifier for single-entry (Einzeleintragung) holders.';
CREATE UNIQUE INDEX idx_asset_holder_reference
    ON asset_holder (asset_id, holder_reference)
    WHERE holder_reference IS NOT NULL;
CREATE UNIQUE INDEX idx_holder_wallet ON asset_holder (asset_id, wallet_address) WHERE removed_at IS NULL;

-- ── Position history (T3-06) ────────────────────────────────────────────────
-- One row per state of an asset_holder row; the as-of position is the latest row with
-- valid_from < cut-off, counted only when removed_at is null or after the cut-off.
-- No FK to asset_holder: the history is append-only evidence and must outlive any row cleanup.
CREATE TABLE asset_holder_position_history (
    id             BIGSERIAL PRIMARY KEY,
    holder_id      UUID           NOT NULL,
    asset_id       UUID           NOT NULL,
    investor_id    UUID           NOT NULL,
    wallet_address VARCHAR(66)    NOT NULL,
    holder_kind    VARCHAR(20)    NOT NULL,
    nominal_amount NUMERIC(96,18) NOT NULL,
    removed_at     TIMESTAMPTZ,
    valid_from     TIMESTAMPTZ    NOT NULL,
    -- true for a row that was written to seed the history of an already existing holder (back-dated to
    -- created_at) rather than by a position change. Record dates before recorded_at may therefore see a
    -- later value than the true one.
    backfilled     BOOLEAN        NOT NULL DEFAULT false,
    recorded_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_holder_position_history_asof
    ON asset_holder_position_history (asset_id, holder_id, valid_from DESC);

CREATE FUNCTION capture_asset_holder_position() RETURNS trigger AS $$
BEGIN
    INSERT INTO asset_holder_position_history
        (holder_id, asset_id, investor_id, wallet_address, holder_kind, nominal_amount, removed_at, valid_from)
    VALUES
        (NEW.id, NEW.asset_id, NEW.investor_id, NEW.wallet_address, NEW.holder_kind, NEW.nominal_amount,
         NEW.removed_at, clock_timestamp());
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_asset_holder_position_insert
    AFTER INSERT ON asset_holder
    FOR EACH ROW EXECUTE FUNCTION capture_asset_holder_position();

CREATE TRIGGER trg_asset_holder_position_update
    AFTER UPDATE OF nominal_amount, removed_at, holder_kind ON asset_holder
    FOR EACH ROW
    WHEN (OLD.nominal_amount IS DISTINCT FROM NEW.nominal_amount
          OR OLD.removed_at IS DISTINCT FROM NEW.removed_at
          OR OLD.holder_kind IS DISTINCT FROM NEW.holder_kind)
    EXECUTE FUNCTION capture_asset_holder_position();

-- Light table on which an issuer can only ASK for a register-entry change; the operator executes
-- (or rejects) it against the instruction the issuer relays.
CREATE TABLE holder_change_request (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    request_type          VARCHAR(30) NOT NULL,
    holder_id             UUID,
    payload               JSONB NOT NULL DEFAULT '{}'::jsonb,
    instructing_party     VARCHAR(40) NOT NULL,
    instruction_reference TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    requested_by          UUID,
    requested_by_role     VARCHAR(40),
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by            UUID,
    decided_at            TIMESTAMPTZ,
    decision_reason       TEXT,
    resulting_change_id   UUID,
    CONSTRAINT chk_holder_change_request_type CHECK (request_type IN ('ADD_HOLDER','UPDATE_ATTRIBUTES')),
    CONSTRAINT chk_holder_change_request_status CHECK (status IN ('REQUESTED','EXECUTED','REJECTED'))
);
CREATE INDEX idx_holder_change_request_asset ON holder_change_request (asset_id, status);

-- One row per executed register-entry change: who instructed it, on what reference, before/after.
CREATE TABLE asset_holder_change (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    holder_id             UUID NOT NULL,
    change_type           VARCHAR(30) NOT NULL,
    instructing_party     VARCHAR(40) NOT NULL,
    instruction_reference TEXT NOT NULL,
    before_state          JSONB,
    after_state           JSONB,
    actor_id              UUID,
    actor_role            VARCHAR(40),
    approver_id           UUID,
    change_request_id     UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_asset_holder_change_holder ON asset_holder_change (holder_id);
CREATE INDEX idx_asset_holder_change_asset ON asset_holder_change (asset_id, created_at);

CREATE TABLE mint_control_rule (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    target_address       VARCHAR(66) NOT NULL,
    rule_type            VARCHAR(30) NOT NULL,
    max_amount           NUMERIC(38,18),
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_rule_type CHECK (
        rule_type IN ('MINT_ALLOWANCE','AUTO_APPROVE_TRANSFER','AUTO_APPROVE_BURN')
    )
);
CREATE INDEX idx_mint_rule_deployment ON mint_control_rule (asset_deployment_id);
CREATE INDEX idx_mint_rule_target     ON mint_control_rule (target_address);

-- Bond terms
CREATE TABLE asset_bond_terms (
    asset_id          UUID PRIMARY KEY REFERENCES asset(id),
    face_value        NUMERIC(38,18) NOT NULL,
    currency_iso      VARCHAR(3) NOT NULL,
    issue_date        DATE NOT NULL,
    maturity_date     DATE NOT NULL,
    coupon_rate       NUMERIC(10,8),
    reference_rate    VARCHAR(32),
    spread            NUMERIC(10,8),
    day_count         VARCHAR(20) NOT NULL,
    payment_frequency VARCHAR(16) NOT NULL,
    callable          BOOLEAN NOT NULL DEFAULT FALSE,
    call_schedule     JSONB,
    bond_status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- Fraction of face value paid at issue (e.g. 0.80 for 80%). The entire point of a
    -- zero-coupon bond; fixed/floating bonds leave it at par.
    issue_price       NUMERIC(10, 8) NOT NULL DEFAULT 1.0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- T3-04: bond terms carry the conventions a coupon schedule is generated from (ICMA / TARGET2
    -- defaults), and each generated coupon row carries its accrual period, record and announcement
    -- dates and the unrounded day-count fraction. scheduled_date stays the adjusted payment date so
    -- existing queries keep working; rows without a generated schedule keep NULL period columns.
    business_day_convention VARCHAR(24) NOT NULL DEFAULT 'MODIFIED_FOLLOWING',
    holiday_calendar        VARCHAR(16) NOT NULL DEFAULT 'TARGET2',
    record_date_offset_bd   INTEGER     NOT NULL DEFAULT 1,
    announcement_lead_bd    INTEGER     NOT NULL DEFAULT 5,
    interest_grace_days     INTEGER     NOT NULL DEFAULT 30,
    principal_grace_days    INTEGER     NOT NULL DEFAULT 7,
    stub_rule               VARCHAR(16) NOT NULL DEFAULT 'SHORT_FIRST',
    CONSTRAINT ck_bond_terms_schedule_offsets CHECK (
        record_date_offset_bd >= 0 AND announcement_lead_bd >= 0
        AND interest_grace_days >= 0 AND principal_grace_days >= 0)
);

CREATE TABLE vault_nav_strike (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      UUID NOT NULL REFERENCES asset(id),
    strike_id     BIGINT NOT NULL,
    nav_per_share NUMERIC(38,18) NOT NULL,
    effective_at  TIMESTAMPTZ NOT NULL,
    report_hash   BYTEA,
    report_doc_id UUID,
    struck_by     UUID NOT NULL,
    struck_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tx_hash       VARCHAR(80),
    -- One row per NAV-strike attempt; tx_hash already existed but was never populated.
    chain_config_id UUID REFERENCES chain_config(id),
    block_number BIGINT,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    -- Compensation must only undo the exact block occurrence that produced the current projection.
    -- A height (or transaction hash) can be reused when a transaction is re-mined in a replacement
    -- block, so the causal block hash is persisted beside every remaining reversible projection.
    block_hash VARCHAR(128),
    UNIQUE (asset_id, strike_id)
);
CREATE INDEX ON vault_nav_strike (asset_id, effective_at DESC);
CREATE INDEX idx_vault_nav_strike_pending
    ON vault_nav_strike (tx_hash)
    WHERE confirmed = false AND tx_hash IS NOT NULL;

-- Vault state (ERC-4626 / ERC-7540)
CREATE TABLE asset_vault_state (
    asset_id               UUID PRIMARY KEY REFERENCES asset(id),
    underlying_asset_id    UUID REFERENCES asset(id),
    deposit_cap            NUMERIC(78,0),
    min_settlement_delay   INTEGER,
    latest_nav_per_share   NUMERIC(38,18),
    latest_nav_strike_at   TIMESTAMPTZ,
    latest_nav_report_hash BYTEA,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- setDepositCap has no history table of its own (unlike NAV strikes), so the in-flight value and
    -- its tx are tracked directly on the state row; deposit_cap_tx_hash IS NOT NULL is itself the
    -- "pending" signal (cleared once VaultConfirmationListener copies pending_deposit_cap into
    -- deposit_cap on confirmation) — no separate boolean needed since, unlike vault_request, this
    -- table has nothing else to poll concurrently.
    pending_deposit_cap NUMERIC(78, 0),
    deposit_cap_tx_hash VARCHAR(80),
    deposit_cap_chain_config_id UUID REFERENCES chain_config(id),
    deposit_cap_block_number BIGINT,
    deposit_cap_block_hash VARCHAR(128),
    -- Denormalized vault projections need an unambiguous owner. Business effective_at is not unique,
    -- so it cannot identify which NAV strike may be compensated after a reorg.
    latest_nav_strike_id UUID REFERENCES vault_nav_strike(id),
    -- Preserve the transaction provenance of the current deposit-cap projection. This is separate
    -- from deposit_cap_tx_hash, which intentionally represents only an in-flight transaction.
    deposit_cap_confirmed_tx_hash VARCHAR(80),
    CONSTRAINT chk_deposit_cap_complete_block_identity CHECK (
            (deposit_cap_chain_config_id IS NULL
                AND deposit_cap_block_number IS NULL
                AND deposit_cap_block_hash IS NULL
                AND deposit_cap_confirmed_tx_hash IS NULL)
            OR
            (deposit_cap_chain_config_id IS NOT NULL
                AND deposit_cap_block_number IS NOT NULL
                AND deposit_cap_block_hash IS NOT NULL
                AND deposit_cap_confirmed_tx_hash IS NOT NULL)
        ),
    -- A deposit-cap intent is one inseparable pair. A partial pair is refused rather than silently repaired,
    -- because either value could describe an already-broadcast transaction.
    CONSTRAINT chk_deposit_cap_pending_pair CHECK (
            (pending_deposit_cap IS NULL AND deposit_cap_tx_hash IS NULL)
            OR (pending_deposit_cap IS NOT NULL AND deposit_cap_tx_hash IS NOT NULL)
        )
);
CREATE INDEX idx_asset_vault_state_pending_deposit_cap
    ON asset_vault_state (deposit_cap_tx_hash)
    WHERE deposit_cap_tx_hash IS NOT NULL;

CREATE TABLE vault_request (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id       UUID NOT NULL REFERENCES asset(id),
    request_id     NUMERIC(78,0) NOT NULL,
    request_type   VARCHAR(8) NOT NULL,
    controller_addr VARCHAR(80) NOT NULL,
    owner_addr     VARCHAR(80) NOT NULL,
    asset_amount   NUMERIC(78,0),
    share_amount   NUMERIC(78,0),
    request_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fulfilled_at   TIMESTAMPTZ,
    fulfilled_tx   VARCHAR(80),
    nav_at_fulfill NUMERIC(38,18),
    -- request_status now deliberately stays PENDING through the confirmation window for both the
    -- fulfil and the cancel path — confirmed distinguishes "submitted, awaiting confirmation" from
    -- "genuinely never touched". cancelled_tx is new (fulfilled_tx already existed but, like
    -- vault_nav_strike.tx_hash, was never populated); the two tx columns are mutually exclusive per
    -- row since a request can only ever be fulfilled or cancelled once.
    chain_config_id UUID REFERENCES chain_config(id),
    block_number BIGINT,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    cancelled_tx VARCHAR(80),
    block_hash VARCHAR(128),
    -- Who funded a deposit request (EwpgERC7540.depositRequestPayer) — cancel refunds go here, not
    -- to the owner, so the operator's pre-cancel freeze check must look at this address.
    payer_addr VARCHAR(80),
    -- Exact occurrence of the DepositRequested/RedeemRequested log that created the row, so a
    -- retraction of that block can be matched to this row (VAULT_REQUEST_INGESTED effect).
    requested_tx VARCHAR(80),
    requested_block_number BIGINT,
    requested_block_hash VARCHAR(128),
    -- Registry force-cancel (ForcedRequestCancelled): escrow destination and the stated legal basis.
    forced_to_addr VARCHAR(80),
    legal_basis VARCHAR(1000),
    -- Set when a confirmed fulfilment could not be reconciled with its on-chain *Fulfilled event
    -- (T1-08) — the row needs a human to look at it. NULL = nothing to review.
    review_note VARCHAR(500)
);
CREATE INDEX ON vault_request (asset_id, request_status);
CREATE INDEX idx_vault_request_pending_fulfill
    ON vault_request (fulfilled_tx)
    WHERE confirmed = false AND fulfilled_tx IS NOT NULL;
CREATE INDEX idx_vault_request_pending_cancel
    ON vault_request (cancelled_tx)
    WHERE confirmed = false AND cancelled_tx IS NOT NULL;
CREATE UNIQUE INDEX uq_vault_request_key
    ON vault_request (asset_id, COALESCE(chain_config_id, '00000000-0000-0000-0000-000000000000'::uuid), request_id);

-- Per-deployment log-scan cursor: the highest block whose vault logs have been ingested.
CREATE TABLE vault_request_ingest_cursor (
    asset_deployment_id UUID PRIMARY KEY REFERENCES asset_deployment(id),
    last_scanned_block  BIGINT NOT NULL,
    last_run_at         TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ERC-3525 / Starknet SFT slots
CREATE TABLE asset_slot (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id   UUID NOT NULL REFERENCES asset(id),
    slot_id    NUMERIC(78,0) NOT NULL,
    name       VARCHAR(200),
    metadata   JSONB,
    supply_cap NUMERIC(78,0),
    paused     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, slot_id)
);
CREATE INDEX ON asset_slot (asset_id);

CREATE TABLE asset_token_unit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id      UUID NOT NULL REFERENCES asset(id),
    slot_id       NUMERIC(78,0) NOT NULL,
    token_id      NUMERIC(78,0) NOT NULL,
    owner_addr    VARCHAR(80),
    token_value   NUMERIC(78,0) NOT NULL DEFAULT 0,
    frozen        BOOLEAN NOT NULL DEFAULT FALSE,
    freeze_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (asset_id, token_id)
);
CREATE INDEX ON asset_token_unit (asset_id, slot_id);

CREATE TABLE asset_coupon_payment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID NOT NULL REFERENCES asset(id),
    slot_id         NUMERIC(78,0),
    period_no       INTEGER NOT NULL,
    scheduled_date  DATE NOT NULL,
    paid_date       DATE,
    amount_per_unit NUMERIC(38,18),
    coupon_status   VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    tx_ref          VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    period_start       DATE,
    period_end         DATE,
    unadjusted_date    DATE,
    record_date        DATE,
    announcement_date  DATE,
    day_count_fraction NUMERIC(38,18),
    schedule_version   INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX ON asset_coupon_payment (asset_id, coupon_status);

-- 3. One row per (asset, deployment, wallet) burn the redemption dispatched. The unique key makes dispatch idempotent
--    (a redelivered redemption event finds the row and never burns twice); the row tracks the burn to its outcome.
CREATE TABLE asset_redemption_burn (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID NOT NULL REFERENCES asset(id),
    deployment_id   UUID NOT NULL,
    wallet_address  VARCHAR(128) NOT NULL,
    amount          NUMERIC(96,18) NOT NULL,
    tx_id           UUID,
    status          VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ,
    CONSTRAINT chk_asset_redemption_burn_status CHECK (status IN ('SUBMITTED', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_asset_redemption_burn_amount CHECK (amount > 0),
    CONSTRAINT uq_asset_redemption_burn UNIQUE (asset_id, deployment_id, wallet_address)
);
CREATE INDEX idx_asset_redemption_burn_open ON asset_redemption_burn (asset_id) WHERE status <> 'CONFIRMED';
CREATE INDEX idx_asset_redemption_burn_tx   ON asset_redemption_burn (tx_id) WHERE tx_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- TOKEN TRANSFERS & INDEXING
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE token_transfer (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    asset_id         UUID        REFERENCES asset(id),
    deployment_id    UUID        REFERENCES asset_deployment(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    -- Sized for EVM hashes, Solana signatures, Starknet felts, and Stellar G-addresses.
    -- Non-EVM indexers may use slots/cursors and omit block or counterparty fields.
    contract_address VARCHAR(128) NOT NULL,
    from_address     VARCHAR(128),
    to_address       VARCHAR(128),
    token_id         NUMERIC,
    amount           NUMERIC(96,18),
    event_type       VARCHAR(10) NOT NULL,
    tx_hash          VARCHAR(128) NOT NULL,
    block_number     BIGINT,
    log_index        INT,
    slot             BIGINT,
    occurred_at      TIMESTAMPTZ NOT NULL,
    explorer_tx_url  VARCHAR(600),
    raw_data         JSONB,
    finality_status  VARCHAR(16) NOT NULL DEFAULT 'FINALIZED',
    block_hash       VARCHAR(128),
    CONSTRAINT chk_event_type     CHECK (event_type IN ('MINT','TRANSFER','BURN')),
    CONSTRAINT token_transfer_pkey PRIMARY KEY (id, occurred_at),
    CONSTRAINT chk_transfer_finality
        CHECK (finality_status IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED')),
    CONSTRAINT chk_token_transfer_normalized_hex_block_hash CHECK (
            block_hash IS NULL
            OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
            OR block_hash = lower(block_hash)
        ),
    CONSTRAINT chk_token_transfer_normalized_hex_tx_hash CHECK (
            tx_hash !~ '^0[xX][0-9A-Fa-f]+$'
            OR tx_hash = lower(tx_hash)
        ),
    CONSTRAINT uq_transfer_solana
        UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot, log_index, contract_address, occurred_at),
    CONSTRAINT uq_transfer_evm
        UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index, block_hash, contract_address, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX idx_transfer_asset      ON token_transfer (asset_id);
CREATE INDEX idx_transfer_deployment ON token_transfer (deployment_id);
CREATE INDEX idx_transfer_chain      ON token_transfer (chain_config_id, block_number DESC);
CREATE INDEX idx_transfer_contract   ON token_transfer (contract_address, occurred_at DESC);
CREATE INDEX idx_transfer_from       ON token_transfer (from_address);
CREATE INDEX idx_transfer_to         ON token_transfer (to_address);
CREATE INDEX idx_transfer_finality ON token_transfer (chain_config_id, finality_status, block_number)
    WHERE finality_status <> 'FINALIZED';

-- P4-06: transfers indexed before their deployment row existed have deployment_id NULL and are
-- linked afterwards by a repair pass keyed on (chain, address).
CREATE INDEX idx_transfer_unlinked ON token_transfer (chain_config_id, lower(contract_address))
    WHERE deployment_id IS NULL;

-- StarknetOccurredAtRepair looks for rows that still carry a poll-time occurred_at (no raw_data.blockTimestamp
-- marker). Without an index that scan walked every transfer row every 10 minutes; this partial index holds
-- only the still-unrepaired rows, so the scan is bounded by the remaining work and is empty once done.
CREATE INDEX IF NOT EXISTS idx_transfer_occurred_at_unrepaired
    ON token_transfer (chain_config_id, block_number)
    WHERE block_number IS NOT NULL AND (raw_data IS NULL OR raw_data->>'blockTimestamp' IS NULL);

CREATE TABLE token_transfer_default PARTITION OF token_transfer DEFAULT;

CREATE TABLE indexer_state (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID        NOT NULL REFERENCES chain_config(id),
    indexer_type          VARCHAR(30) NOT NULL,
    last_synced_block     BIGINT,
    last_final_block      BIGINT,
    -- Widened to 200 (from 100) — reused as a generic string cursor (Solana signature, Canton
    -- ledger offset, Horizon paging token), matching what the JPA entity always declared.
    last_synced_signature VARCHAR(200),
    last_synced_at        TIMESTAMPTZ,
    last_error            TEXT,
    consecutive_errors    INT         NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Wave 0b H7: the corporate-action freshness gate compared the WALL-CLOCK time of the last holder sync with the
    -- record-date cut-off. A lagging indexer (or a sync that ran against stale data) passed it although the chain had
    -- never been indexed past the cut-off. The indexer now records the BLOCK TIME of the head block it has processed;
    -- the gate compares that with the cut-off.
    last_synced_block_time TIMESTAMPTZ,
    CONSTRAINT uq_indexer_state   UNIQUE (chain_config_id, indexer_type),
    CONSTRAINT chk_indexer_type   CHECK (indexer_type IN ('GRAPH_NODE','SOLANA_GEYSER','SOLANA_POLL','CANTON_STREAM','STARKNET_POLL','STELLAR_HORIZON')),
    CONSTRAINT chk_indexer_status CHECK (status IN ('ACTIVE','PAUSED','ERROR'))
);
CREATE INDEX idx_indexer_state_chain  ON indexer_state (chain_config_id);
CREATE INDEX idx_indexer_state_status ON indexer_state (status);
COMMENT ON COLUMN indexer_state.last_synced_block_time IS
    'Block timestamp of last_synced_block (chain time, not wall-clock); NULL when the indexer cannot report it.';

-- 3) Per-deployment ingestion cursor (Stellar Stage 1). Horizon's operation feed was read with one
--    chain-wide minimum cursor; a deployment added later was only scanned from that shared position.
--    Each deployment now owns its cursor, so it is scanned from its own beginning.
CREATE TABLE indexer_deployment_cursor (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id   UUID NOT NULL REFERENCES asset_deployment(id),
    indexer_type    VARCHAR(30) NOT NULL,
    cursor_value    VARCHAR(200),
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_indexer_deployment_cursor UNIQUE (deployment_id, indexer_type),
    CONSTRAINT chk_indexer_deployment_cursor_type CHECK (indexer_type IN ('STELLAR_HORIZON', 'CANTON_STREAM', 'SOLANA_POLL'))
);

-- Per-mint Solana cursor. indexer_state above is keyed only by (chain, indexer_type), which is
-- too coarse for Solana: one shared cursor across every tracked mint on a chain permanently
-- freezes the "until" boundary and silently loses transfer history once a mint's backlog
-- exceeds MAX_SIGNATURES_PER_POLL. Each mint therefore gets its own advancing cursor.
CREATE TABLE solana_mint_sync_cursor (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    mint_address          VARCHAR(64) NOT NULL,
    last_synced_signature VARCHAR(200),
    last_synced_at        TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_solana_mint_sync_cursor UNIQUE (chain_config_id, mint_address)
);

-- Durable mirror of the Canton Holdings the indexer has seen Created and not yet seen Archived.
-- Daml's Archived ledger event carries only the contract ID, never its former argument payload,
-- so without this table an Archived event cannot be attributed to an instrument/owner/amount.
CREATE TABLE canton_holding_snapshot (
    contract_id      VARCHAR(255) PRIMARY KEY,
    chain_config_id  UUID NOT NULL REFERENCES chain_config(id),
    instrument       VARCHAR(255) NOT NULL,
    owner            VARCHAR(255) NOT NULL,
    amount           NUMERIC(38,18) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_canton_holding_snapshot_chain ON canton_holding_snapshot (chain_config_id);

-- Per-deployment cursor for ConfidentialTravelRuleScreeningService: the last indexed block
-- screened for Travel Rule obligations, so each scheduled run only decrypts and evaluates
-- confidential transfer/mint events new since the previous run.
CREATE TABLE confidential_transfer_screening_state (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    last_screened_block  BIGINT NOT NULL DEFAULT 0,
    last_run_at          TIMESTAMPTZ,
    last_error           TEXT,
    -- Consecutive runs that failed to resolve the earliest unresolved event. The cursor must
    -- not advance unconditionally past a failed decrypt (a transient relayer/KMS hiccup would
    -- permanently and silently skip screening for that transfer), but nor may it retry forever
    -- (a non-transient failure would wedge the service). This bounds the retries; on exhaustion
    -- the service advances past the event and logs at ERROR.
    consecutive_decrypt_failures INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_conf_transfer_screening_deployment UNIQUE (asset_deployment_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- ERC-3643 / ONCHAIN IDENTITY
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE onchain_identity (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id  UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    identity_address VARCHAR(66) NOT NULL,
    deployed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deployed_by_tx   VARCHAR(66),
    deployed_block_number BIGINT,
    deployed_block_hash VARCHAR(128),
    UNIQUE (legal_entity_id, chain_config_id)
);
CREATE INDEX idx_onchain_identity_entity ON onchain_identity (legal_entity_id);
CREATE INDEX idx_onchain_identity_chain  ON onchain_identity (chain_config_id);

CREATE TABLE onchain_claim (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    onchain_identity_id UUID        NOT NULL REFERENCES onchain_identity(id),
    topic               BIGINT      NOT NULL,
    topic_label         VARCHAR(50),
    issuer_address      VARCHAR(66) NOT NULL,
    claim_id            VARCHAR(66),
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    tx_hash             VARCHAR(66),
    claim_data          TEXT,
    claim_signature     TEXT,
    -- ClaimIssuanceService.issueClaim/revokeClaim (via Erc3643DeploymentService.issueKycClaim/
    -- revokeKycClaim) submitted a real EVM transaction (or, worse, silently skipped it entirely when
    -- the target identity was still 0x-PENDING-...) and then immediately persisted/mutated
    -- onchain_claim as if the on-chain call had already succeeded — before any receipt existed, and
    -- in the PENDING-identity case before anything was ever submitted at all. This mirrors the exact
    -- "optimistic write with no confirmation-gated moment" gap closed for
    -- erc3643_identity_registry / the vault-admin services; see Erc3643ClaimConfirmationListener for
    -- the polling/confirmation side of this fix.
    chain_config_id UUID REFERENCES chain_config(id),
    block_number BIGINT,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    -- revocation_tx_hash IS NOT NULL is itself the "revocation pending" signal — revoked_at is only
    -- ever set once Erc3643ClaimConfirmationListener confirms this tx, so there is no separate
    -- "revocation confirmed" boolean needed (unlike issuance, nothing else on this row is concurrently
    -- pending once a revocation is in flight: confirmed is already true by then).
    revocation_tx_hash VARCHAR(80),
    block_hash VARCHAR(128),
    revocation_chain_config_id UUID REFERENCES chain_config(id),
    revocation_block_number BIGINT,
    revocation_block_hash VARCHAR(128),
    -- Issuer-level revocation (ClaimIssuer.revokeClaimBySignature). removeClaim alone is reversible:
    -- the issuer still vouches for the signature, so a CLAIM key on the identity can re-add it.
    issuer_revocation_tx_hash VARCHAR(80),
    issuer_revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_claim_identity ON onchain_claim (onchain_identity_id);
CREATE INDEX idx_claim_topic    ON onchain_claim (topic);
CREATE INDEX idx_onchain_claim_pending_issuance
    ON onchain_claim (tx_hash)
    WHERE confirmed = false AND tx_hash IS NOT NULL;
CREATE INDEX idx_onchain_claim_pending_revocation
    ON onchain_claim (revocation_tx_hash)
    WHERE revoked_at IS NULL AND revocation_tx_hash IS NOT NULL;
CREATE INDEX idx_onchain_claim_issuer_revocation_pending
    ON onchain_claim (issuer_revocation_tx_hash)
    WHERE issuer_revoked_at IS NULL AND issuer_revocation_tx_hash IS NOT NULL;

-- T2-19: KYC expiry / rejection reaches the chain.
--
-- KycChainPropagationListener records one row per (legal entity, chain) when an entity's KYC
-- expires or is rejected, and drives it until every on-chain step is submitted:
--   * OrgRegistry.suspendOrg (via the existing fail-closed OrgRegistrationService.suspend path);
--   * ONCHAINID.removeClaim + ClaimIssuer.revokeClaimBySignature for the KYC (1) / AML (2) claims.
-- Every step is idempotent; the row only tracks whether the pass is complete and why not.
-- Reversal is never automatic: it goes through the existing 4-eyes re-approval / reinstate path.
CREATE TABLE kyc_chain_propagation (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id   UUID        NOT NULL REFERENCES chain_config(id),
    -- KYC_EXPIRED | KYC_REJECTED
    trigger_reason    VARCHAR(32) NOT NULL,
    -- PENDING | COMPLETED | FAILED | SUPERSEDED (KYC re-approved before completion)
    status            VARCHAR(24) NOT NULL,
    -- SUSPEND_SUBMITTED | ALREADY_SUSPENDED | NOT_REGISTERED | WAITING (registration/reinstate in flight)
    org_action        VARCHAR(32),
    unresolved_claims INT         NOT NULL DEFAULT 0,
    attempts          INT         NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uq_kyc_chain_propagation_entity_chain UNIQUE (legal_entity_id, chain_config_id)
);
CREATE INDEX idx_kyc_chain_propagation_open
    ON kyc_chain_propagation (status)
    WHERE status IN ('PENDING', 'FAILED');

CREATE TABLE erc3643_suite (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id       UUID        NOT NULL UNIQUE REFERENCES asset_deployment(id),
    token_address             VARCHAR(66),
    identity_registry_address VARCHAR(66),
    identity_registry_storage VARCHAR(66),
    compliance_address        VARCHAR(66),
    claim_topics_registry     VARCHAR(66),
    trusted_issuers_registry  VARCHAR(66),
    factory_tx_hash           VARCHAR(66),
    is_confidential           BOOLEAN     NOT NULL DEFAULT false,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE erc3643_compliance_module (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id          UUID        NOT NULL REFERENCES erc3643_suite(id),
    module_address    VARCHAR(66) NOT NULL,
    module_type       VARCHAR(50) NOT NULL,
    parameters        JSONB,
    max_investors     INTEGER,
    max_balance       NUMERIC(38,0),
    transfer_cooldown INTEGER,
    blocked_countries SMALLINT[],
    added_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at        TIMESTAMPTZ
);

CREATE TABLE erc3643_trusted_issuer (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id        UUID        NOT NULL REFERENCES erc3643_suite(id),
    issuer_address  VARCHAR(66) NOT NULL,
    claim_topics    BIGINT[]    NOT NULL DEFAULT '{}',
    legal_entity_id UUID        REFERENCES legal_entity(id),
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at      TIMESTAMPTZ
);

CREATE TABLE erc3643_claim_topic (
    id       UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id UUID   NOT NULL REFERENCES erc3643_suite(id),
    topic    BIGINT NOT NULL,
    label    VARCHAR(50),
    UNIQUE (suite_id, topic)
);

CREATE TABLE erc3643_identity_registry (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id            UUID        NOT NULL REFERENCES erc3643_suite(id),
    wallet_address      VARCHAR(66) NOT NULL,
    onchain_identity_id UUID        NOT NULL REFERENCES onchain_identity(id),
    country_code        SMALLINT,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by_tx    VARCHAR(66),
    removed_at          TIMESTAMPTZ,
    -- erc3643_identity_registry.register/deleteIdentity are optimistic writes (see
    -- IdentityRegistryService's javadoc) with no confirmation-gated moment on their own —
    -- unlike blockchain_transaction/asset_deployment/orgidentity's *_registration tables, nothing here
    -- ever re-verified against finality, so a reorg un-mining registerIdentity/deleteIdentity could
    -- leave the register asserting a state the chain no longer agrees with. These columns give
    -- Erc3643IdentityRegistryConfirmationListener what it needs to close that gap: chain_config_id to
    -- locate the confirming block, removed_by_tx to track the deleteIdentity call the same way
    -- registered_by_tx already tracks registerIdentity, and the two *_confirmed flags to scope its
    -- polling query so it shrinks over time instead of re-scanning every entry ever written.
    chain_config_id UUID REFERENCES chain_config(id),
    removed_by_tx VARCHAR(66),
    registration_confirmed BOOLEAN NOT NULL DEFAULT false,
    removal_confirmed BOOLEAN NOT NULL DEFAULT false,
    registration_block_number BIGINT,
    registration_block_hash VARCHAR(128),
    removal_block_number BIGINT,
    removal_block_hash VARCHAR(128)
);

-- Only active registrations are unique; removed registrations remain as history.
CREATE UNIQUE INDEX erc3643_identity_registry_active_unique
    ON erc3643_identity_registry (suite_id, wallet_address)
    WHERE removed_at IS NULL;
CREATE INDEX idx_erc3643_ir_suite    ON erc3643_identity_registry (suite_id);
CREATE INDEX idx_erc3643_ir_identity ON erc3643_identity_registry (onchain_identity_id);
CREATE INDEX idx_erc3643_identity_registry_pending_registration
    ON erc3643_identity_registry (registered_by_tx)
    WHERE registration_confirmed = false AND registered_by_tx IS NOT NULL;
CREATE INDEX idx_erc3643_identity_registry_pending_removal
    ON erc3643_identity_registry (removed_by_tx)
    WHERE removal_confirmed = false AND removed_by_tx IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- BLOCKCHAIN TRANSACTIONS & WALLETS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE blockchain_transaction (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    tx_hash          VARCHAR(66),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    chain            VARCHAR(30),
    network          VARCHAR(30),
    contract_address VARCHAR(42),
    deployment_id    UUID,
    asset_id         UUID,
    method_name      VARCHAR(100),
    params           JSONB,
    actor_name       VARCHAR(255),
    actor_role       VARCHAR(30),
    gas_used         BIGINT,
    block_number     BIGINT,
    block_hash       VARCHAR(66),
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    ops_note         TEXT,
    ops_reviewed_at  TIMESTAMPTZ,
    ops_reviewed_by  UUID,
    -- Links blockchain_transaction back to chain_config so a reorg retraction (chainConfigId +
    -- forkBlockNumber) can find affected rows without going through chain/network string matching.
    -- Nullable: a row without it is not discoverable by the compensation sweep (see
    -- BlockchainTransactionService.record's javadoc for how new rows populate it).
    chain_config_id UUID REFERENCES chain_config(id),
    -- A confidential (Zama fhEVM) forcedTransfer / confidentialBurn is an all-or-nothing FHE select:
    -- when the holder's encrypted balance is below the ordered amount the contract moves 0 and the
    -- transaction still succeeds. `status = SUCCESS` therefore does not mean the court-ordered
    -- correction was executed. ConfidentialForcedOpVerifier decrypts the moved-amount handle from
    -- the receipt (operator-viewer ACL) and records the verified outcome here.
    --
    -- NULL = not applicable, or verification still pending (see execution_outcome_attempts).
    -- Existing SUCCESS rows for confidentialForcedTransfer / confidentialForceBurn stay NULL and are
    -- picked up by the verifier on its next run, so historic corrections are verified as well.
    execution_outcome            VARCHAR(40),
    execution_outcome_attempts   INT NOT NULL DEFAULT 0,
    execution_outcome_checked_at TIMESTAMPTZ,
    -- P4C-4: second approver and optional case reference of the request that submitted a chain tx.
    approver_id     UUID,
    case_reference  VARCHAR(200),
    -- blockchain_transaction: TIMEOUT is no longer terminal. The poller keeps reading TIMEOUT rows for the
    -- late-mined window and records when a receipt finally arrived; REPLACED (new status value, no CHECK
    -- constraint exists on this column) means the nonce was consumed by a different transaction.
    late_mined_at       TIMESTAMPTZ,
    replaced_by_tx_hash VARCHAR(66),
    mined_tx_hash       VARCHAR(66),
    idempotency_key VARCHAR(400),
    CONSTRAINT blockchain_transaction_pkey PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX idx_btx_deployment ON blockchain_transaction (deployment_id);
CREATE INDEX idx_btx_asset      ON blockchain_transaction (asset_id);
CREATE INDEX idx_btx_actor      ON blockchain_transaction (actor_name);
CREATE INDEX idx_btx_status     ON blockchain_transaction (status) WHERE status = 'PENDING';
CREATE INDEX idx_btx_created    ON blockchain_transaction (created_at DESC);
CREATE INDEX idx_blockchain_transaction_chain_config_block
    ON blockchain_transaction (chain_config_id, block_number);
CREATE INDEX idx_btx_timeout ON blockchain_transaction (completed_at) WHERE status = 'TIMEOUT';
CREATE INDEX idx_blockchain_transaction_idempotency_key
    ON blockchain_transaction (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE blockchain_transaction_default PARTITION OF blockchain_transaction DEFAULT;

CREATE TABLE operator_wallet (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(120) NOT NULL UNIQUE,
    type          VARCHAR(10)  NOT NULL,
    address       VARCHAR(64)  NOT NULL,
    keystore_path VARCHAR(255),
    custody_type  VARCHAR(20)  NOT NULL DEFAULT 'SOFTWARE',
    key_reference VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID,
    -- K6 / Phase 4 (P4C-4, P4C-5)
    --
    -- P4C-5: operator wallet delete becomes a soft delete. The row is tombstoned and its (encrypted)
    -- key material is kept until registerwerk.wallet.retention-days has passed; WalletPurgeJob then
    -- destroys the key blob and hard-deletes the row. Within the window the deletion is reversible.
    deleted_at          TIMESTAMPTZ,
    deleted_by          UUID,
    deleted_approver_id UUID,
    CONSTRAINT chk_wallet_type CHECK (type IN ('EVM','SOLANA')),
    CONSTRAINT uq_wallet_addr  UNIQUE (type, address),
    CONSTRAINT chk_wallet_custody_type
        CHECK (custody_type IN ('SOFTWARE', 'PKCS11', 'KMS')),
    CONSTRAINT chk_wallet_custody_reference CHECK (
        (custody_type = 'SOFTWARE' AND keystore_path IS NOT NULL AND key_reference IS NULL)
        OR
        (custody_type IN ('PKCS11', 'KMS') AND type = 'EVM' AND keystore_path IS NULL AND key_reference IS NOT NULL)
    )
);
CREATE INDEX idx_operator_wallet_deleted ON operator_wallet (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE UNIQUE INDEX uq_operator_wallet_opaque_reference
    ON operator_wallet (key_reference)
    WHERE custody_type IN ('PKCS11', 'KMS');

-- P4C-5: marks that a first wallet has ever existed. Only before this marker may a newly created
-- wallet be auto-promoted to chain default (fresh-install bootstrap); afterwards default switching
-- always goes through the 4-eyes setDefault path.
CREATE TABLE wallet_bootstrap_marker (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wallet_nonce_lease (
    chain_id       BIGINT        NOT NULL,
    sender_address VARCHAR(42)   NOT NULL,
    next_nonce     NUMERIC(78,0) NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, sender_address)
);

CREATE TABLE wallet_chain_default (
    chain_config_id UUID        PRIMARY KEY REFERENCES chain_config(id) ON DELETE CASCADE,
    wallet_id       UUID        NOT NULL REFERENCES operator_wallet(id) ON DELETE RESTRICT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID
);
CREATE INDEX idx_wallet_chain_default_wallet ON wallet_chain_default (wallet_id);

CREATE TABLE address_endpoint (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(10)  NOT NULL,
    owner_id     UUID,
    address      VARCHAR(66)  NOT NULL,
    address_type VARCHAR(10)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    notes        VARCHAR(500),
    risk_level   VARCHAR(10),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_risk_level CHECK (risk_level IS NULL OR risk_level IN ('LOW','MEDIUM','HIGH'))
);
CREATE UNIQUE INDEX idx_endpoint_entity_address
    ON address_endpoint (owner_type, owner_id, address) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX idx_endpoint_operator_address
    ON address_endpoint (owner_type, address) WHERE owner_id IS NULL;
CREATE INDEX idx_endpoint_owner   ON address_endpoint (owner_type, owner_id);
CREATE INDEX idx_endpoint_address ON address_endpoint (address);

-- ═══════════════════════════════════════════════════════════════════════════
-- TRADING
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE company_trader_settings (
    legal_entity_id              UUID PRIMARY KEY REFERENCES legal_entity(id) ON DELETE CASCADE,
    default_payment_option       VARCHAR(30) NOT NULL DEFAULT 'OFFCHAIN_SEPA',
    immediate_settlement_enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                   UUID,
    CONSTRAINT chk_trader_default_payment_option CHECK (
        default_payment_option IN (
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE company_trader_wallet_default (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID        NOT NULL REFERENCES legal_entity(id) ON DELETE CASCADE,
    asset_type      VARCHAR(20),
    target_type     VARCHAR(20) NOT NULL,
    endpoint_id     UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address  VARCHAR(128),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_trader_wallet_asset_type CHECK (
        asset_type IS NULL OR asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trader_wallet_target_type CHECK (target_type IN ('ENDPOINT','CUSTOM_ADDRESS')),
    CONSTRAINT chk_trader_wallet_target_payload CHECK (
        (target_type = 'ENDPOINT' AND endpoint_id IS NOT NULL AND wallet_address IS NULL)
        OR
        (target_type = 'CUSTOM_ADDRESS' AND endpoint_id IS NULL AND wallet_address IS NOT NULL)
    )
);
CREATE UNIQUE INDEX idx_trader_wallet_default_global
    ON company_trader_wallet_default (legal_entity_id) WHERE asset_type IS NULL;
CREATE UNIQUE INDEX idx_trader_wallet_default_asset_type
    ON company_trader_wallet_default (legal_entity_id, asset_type) WHERE asset_type IS NOT NULL;
CREATE INDEX idx_trader_wallet_default_entity ON company_trader_wallet_default (legal_entity_id);

CREATE TABLE trade_listing (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_code         VARCHAR(20)    NOT NULL,
    seller_entity_id   UUID           NOT NULL REFERENCES legal_entity(id),
    seller_holder_id   UUID           NOT NULL REFERENCES asset_holder(id),
    asset_id           UUID           NOT NULL REFERENCES asset(id),
    asset_number       VARCHAR(30)    NOT NULL,
    asset_name         VARCHAR(500)   NOT NULL,
    isin               VARCHAR(12),
    asset_type         VARCHAR(20)    NOT NULL,
    token_standard     VARCHAR(20)    NOT NULL,
    chain              VARCHAR(20),
    status             VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    quantity_total     NUMERIC(96,18) NOT NULL,
    quantity_available NUMERIC(96,18) NOT NULL,
    price_per_unit     NUMERIC(38,18) NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- ── 5A-01 ────────────────────────────────────────────────────────────────────
    allow_instant_settlement BOOLEAN NOT NULL DEFAULT false,
    -- Phase 5 / K2 - trading currency, related-party controls and the venue-perimeter interim.
    --   5A-02  listings and trades carry the settlement currency (and the payment rail for stablecoins);
    --          the executed total is rounded to the currency's minor unit and the rounding is stored.
    --   5A-06  related-party / self-dealing flag on the execution (excluded from the reference price).
    --   5C-06  targeted (bilateral) listings, venue classification snapshot and RTS 22-style order
    --          record fields (who submitted, when, in which classification) - interim, parked T5-06.
    -- A NULL currency means "currency not recorded" and is never rendered as EUR.
    currency             VARCHAR(10),
    payment_rail_code    VARCHAR(40),
    target_entity_id     UUID,
    created_by_actor_id  UUID,
    venue_classification VARCHAR(20),
    CONSTRAINT chk_trade_listing_venue CHECK (
        venue_code IN ('SIMULATED','ASSETERA','ARCHAX','TALOS')
    ),
    CONSTRAINT chk_trade_listing_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_listing_status CHECK (
        status IN ('OPEN','PARTIALLY_FILLED','FILLED','CANCELLED')
    ),
    CONSTRAINT chk_trade_listing_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_trade_listing_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_trade_listing_quantity CHECK (
        quantity_total > 0 AND quantity_available >= 0 AND quantity_available <= quantity_total
    ),
    CONSTRAINT chk_trade_listing_price CHECK (price_per_unit > 0)
);
CREATE INDEX idx_trade_listing_status_created ON trade_listing (status, created_at DESC);
CREATE INDEX idx_trade_listing_seller         ON trade_listing (seller_entity_id, created_at DESC);
CREATE INDEX idx_trade_listing_asset          ON trade_listing (asset_id, created_at DESC);
CREATE INDEX idx_trade_listing_holder         ON trade_listing (seller_holder_id);
CREATE INDEX idx_trade_listing_target ON trade_listing (target_entity_id) WHERE target_entity_id IS NOT NULL;
CREATE INDEX idx_trade_listing_created ON trade_listing (created_at);

CREATE TABLE trade_listing_payment_option (
    trade_listing_id UUID        NOT NULL REFERENCES trade_listing(id) ON DELETE CASCADE,
    payment_option   VARCHAR(30) NOT NULL,
    PRIMARY KEY (trade_listing_id, payment_option),
    CONSTRAINT chk_trade_listing_payment_option CHECK (
        payment_option IN (
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    )
);

CREATE TABLE trade_execution (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id             UUID           NOT NULL REFERENCES trade_listing(id),
    venue_code             VARCHAR(20)    NOT NULL,
    buyer_entity_id        UUID           NOT NULL REFERENCES legal_entity(id),
    seller_entity_id       UUID           NOT NULL REFERENCES legal_entity(id),
    seller_holder_id       UUID           NOT NULL REFERENCES asset_holder(id),
    buyer_holder_id        UUID           REFERENCES asset_holder(id),
    asset_id               UUID           NOT NULL REFERENCES asset(id),
    asset_number           VARCHAR(30)    NOT NULL,
    asset_name             VARCHAR(500)   NOT NULL,
    isin                   VARCHAR(12),
    asset_type             VARCHAR(20)    NOT NULL,
    token_standard         VARCHAR(20)    NOT NULL,
    chain                  VARCHAR(20),
    order_type             VARCHAR(20)    NOT NULL,
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    requested_quantity     NUMERIC(96,18) NOT NULL,
    executed_quantity      NUMERIC(96,18) NOT NULL,
    unit_price             NUMERIC(38,18) NOT NULL,
    total_price            NUMERIC(38,18) NOT NULL,
    payment_option         VARCHAR(30)    NOT NULL,
    settlement_status      VARCHAR(30) NOT NULL DEFAULT 'SETTLED',
    wallet_preference_mode VARCHAR(30)    NOT NULL,
    wallet_endpoint_id     UUID REFERENCES address_endpoint(id) ON DELETE SET NULL,
    wallet_address         VARCHAR(128)   NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    settled_at             TIMESTAMPTZ,
    -- Failure detail retained for FAILED/CANCELLED/REFUNDED executions.
    failure_reason         TEXT,
    -- Evidence of the actual cash leg (a stablecoin tx hash, a SEPA transfer reference, …).
    -- Settling a PENDING trade otherwise required nothing beyond the buyer's own HTTP call,
    -- leaving reconciliation with pure self-attestation to check.
    payment_reference      VARCHAR(255),
    -- Adds the AWAITING_SELLER_CONFIRMATION settlement status: a buyer declaring payment no longer
    -- credits the register directly — the selling company must independently confirm receipt first.
    payment_declared_at TIMESTAMPTZ,
    -- ── 5A-03 ────────────────────────────────────────────────────────────────────
    version BIGINT NOT NULL DEFAULT 0,
    instant_settlement BOOLEAN NOT NULL DEFAULT false,
    dispute_reason VARCHAR(1000),
    unresolved_at TIMESTAMPTZ,
    unresolved_reason VARCHAR(1000),
    buyer_cooldown_until TIMESTAMPTZ,
    currency               VARCHAR(10),
    payment_rail_code      VARCHAR(40),
    -- Stored rounding: the exact product and how it was rounded to total_price.
    total_price_unrounded  NUMERIC(38,18),
    price_rounding_scale   SMALLINT,
    price_rounding_mode    VARCHAR(20),
    related_party          BOOLEAN NOT NULL DEFAULT false,
    related_party_reasons  VARCHAR(300),
    created_by_actor_id    UUID,
    venue_classification   VARCHAR(20),
    CONSTRAINT chk_trade_execution_venue CHECK (
        venue_code IN ('SIMULATED','ASSETERA','ARCHAX','TALOS')
    ),
    CONSTRAINT chk_trade_execution_asset_type CHECK (
        asset_type IN ('EQUITY','BOND','FUND','NOTE','COMMODITY','OTHER')
    ),
    CONSTRAINT chk_trade_execution_token_standard CHECK (
        token_standard IN (
            'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
            'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN',
            'ERC3525','ERC4626','ERC7540',
            'STARKNET_ERC3525',
            'DAML_BOND_FIXED','DAML_BOND_FLOATING','DAML_BOND_ZERO',
            'SPL_2022_BOND','SPL_2022_CONFIDENTIAL'
        )
    ),
    CONSTRAINT chk_trade_execution_chain CHECK (
        chain IS NULL OR chain IN (
            'ETHEREUM','POLYGON','BASE','FHENIX','INCO','SOLANA',
            'ARBITRUM','AVALANCHE','OPTIMISM','STARKNET','STELLAR','CANTON'
        )
    ),
    CONSTRAINT chk_trade_execution_order_type CHECK (
        order_type IN ('MARKET','LIMIT','IOC','FOK')
    ),
    CONSTRAINT chk_trade_execution_payment_option CHECK (
        payment_option IN (
            'NATIVE_CHAIN_CURRENCY','STABLECOIN','CBMT','PONTES_TARGET','OFFCHAIN_SEPA'
        )
    ),
    CONSTRAINT chk_trade_execution_wallet_preference_mode CHECK (
        wallet_preference_mode IN (
            'GLOBAL_DEFAULT','ASSET_TYPE_DEFAULT','ENDPOINT','CUSTOM_ADDRESS'
        )
    ),
    CONSTRAINT chk_trade_execution_quantity CHECK (
        requested_quantity > 0 AND executed_quantity > 0 AND executed_quantity <= requested_quantity
    ),
    CONSTRAINT chk_trade_execution_price CHECK (unit_price > 0 AND total_price > 0),
    CONSTRAINT chk_trade_execution_settlement_status CHECK (
        settlement_status IN ('PENDING','AWAITING_SELLER_CONFIRMATION','PAYMENT_UNRESOLVED',
                              'SETTLED','FAILED','CANCELLED','REFUNDED')
    )
);
CREATE INDEX idx_trade_execution_buyer          ON trade_execution (buyer_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_seller         ON trade_execution (seller_entity_id, created_at DESC);
CREATE INDEX idx_trade_execution_listing        ON trade_execution (listing_id);
CREATE INDEX idx_trade_execution_seller_pending ON trade_execution (seller_holder_id, settlement_status)
    WHERE settlement_status = 'PENDING';
CREATE INDEX idx_trade_execution_pending_created ON trade_execution (created_at)
    WHERE settlement_status = 'PENDING';
CREATE INDEX idx_trade_execution_awaiting_confirmation ON trade_execution (payment_declared_at)
    WHERE settlement_status = 'AWAITING_SELLER_CONFIRMATION';

-- The operator queue and the unresolved-age gauge read only these rows.
CREATE INDEX idx_trade_execution_unresolved ON trade_execution (unresolved_at)
    WHERE settlement_status = 'PAYMENT_UNRESOLVED';

-- ── 5A-06 ────────────────────────────────────────────────────────────────────
-- Reservation caps (open reservations per buyer) and the per-listing cool-down lookup.
CREATE INDEX idx_trade_execution_buyer_listing_status
    ON trade_execution (buyer_entity_id, listing_id, settlement_status);
CREATE INDEX idx_trade_execution_buyer_cooldown
    ON trade_execution (buyer_entity_id, listing_id, buyer_cooldown_until)
    WHERE buyer_cooldown_until IS NOT NULL;

-- Reference price: latest settled trade between unrelated parties.
CREATE INDEX idx_trade_execution_reference_price ON trade_execution (asset_id, settled_at DESC)
    WHERE settlement_status = 'SETTLED' AND NOT related_party;

-- Both parties (and the operator) may add evidence notes to a disputed / unresolved trade.
-- Text only in the interim (no files, parked decision T5-02).
CREATE TABLE trade_execution_note (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id      UUID          NOT NULL REFERENCES trade_execution(id),
    actor_entity_id   UUID,
    actor_user_id     UUID,
    actor_role        VARCHAR(30)   NOT NULL,
    note_text         VARCHAR(2000) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_trade_execution_note_execution ON trade_execution_note (execution_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- EXTERNAL REFERENCES
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE company_external_reference (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    subject_type          VARCHAR(50) NOT NULL,
    subject_id            UUID NOT NULL,
    external_id           VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID,
    CONSTRAINT uq_company_external_reference_subject
        UNIQUE (owner_legal_entity_id, subject_type, subject_id),
    CONSTRAINT chk_company_external_reference_subject_type CHECK (
        subject_type IN (
            'LEGAL_ENTITY','ASSET','ASSET_HOLDER','ERC3643_IDENTITY_REGISTRY_ENTRY'
        )
    )
);
CREATE INDEX idx_company_external_reference_owner
    ON company_external_reference (owner_legal_entity_id);
CREATE INDEX idx_company_external_reference_lookup
    ON company_external_reference (owner_legal_entity_id, external_id);
CREATE INDEX idx_company_external_reference_owner_type
    ON company_external_reference (owner_legal_entity_id, subject_type, updated_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- COMPLIANCE: §16 SPERRVERMERK / NATURAL PERSONS / BENEFICIAL OWNERS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE holder_block (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                UUID,
    asset_id                 UUID,
    wallet_address           TEXT NOT NULL,
    block_type               VARCHAR(30) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    legal_basis              TEXT NOT NULL,
    court_ref                TEXT,
    document_id              UUID,
    starts_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at               TIMESTAMPTZ,
    lifted_at                TIMESTAMPTZ,
    lifted_by                UUID,
    lift_reason              TEXT,
    on_chain_freeze_tx_hash  TEXT,
    created_by               UUID NOT NULL,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Phase 6 K9 (6-25, parked T6-11): Sperrvermerk lifecycle.
    --
    -- A block past expires_at is no longer lifted silently at 03:00 for every type. Types not listed in
    -- registerwerk.sperrvermerk.auto-expire-types (default: none) move to the blocking status EXPIRY_REVIEW and
    -- stay enforced until compliance lifts them with step-up + second approver. holder_block.status has no CHECK
    -- constraint, so the new value needs no DDL.
    expiry_confirmed_by_approver BOOLEAN     NOT NULL DEFAULT FALSE,
    expiry_review_at             TIMESTAMPTZ
);
CREATE INDEX idx_holder_block_wallet  ON holder_block (wallet_address) WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_asset   ON holder_block (asset_id)       WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_entity  ON holder_block (entity_id)      WHERE status = 'ACTIVE';
CREATE INDEX idx_holder_block_expires ON holder_block (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
COMMENT ON COLUMN holder_block.expiry_confirmed_by_approver IS
    'Second approver confirmed the expires_at date against the court/authority order at creation (legal-order types).';
COMMENT ON COLUMN holder_block.expiry_review_at IS
    'When the block passed expires_at and was moved to EXPIRY_REVIEW (still blocking).';

-- EXPIRY_REVIEW blocks are enforced by every gate; the lookups need the same partial indexes as ACTIVE.
CREATE INDEX idx_holder_block_wallet_review ON holder_block (wallet_address) WHERE status = 'EXPIRY_REVIEW';
CREATE INDEX idx_holder_block_entity_review ON holder_block (entity_id)      WHERE status = 'EXPIRY_REVIEW';

-- Review phase 3, T3-15: §16 eWpG Sperrvermerk wallets were stored exactly as typed. The register
-- (asset_holder) stores 0x addresses lowercased, and the gate and the on-chain freeze listener
-- compare exact strings, so a checksum-cased block froze nothing and the gates failed open.
-- The application now normalises on write (shared.AddressNormalizer: trim, lowercase 0x only —
-- base58/base32 addresses are case-sensitive and stay as they are).

-- ACTIVE blocks whose wallet changes were never propagated on-chain. Remember them so
-- kyc.internal.HolderBlockFreezeResyncRunner re-emits the freeze once after deploy.
CREATE TABLE holder_block_freeze_resync (
    holder_block_id          UUID PRIMARY KEY REFERENCES holder_block (id),
    original_wallet_address  TEXT        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at             TIMESTAMPTZ
);

-- H5 (Sperrvermerk on-chain freeze reach): one row per (block, deployment, wallet) that records whether the
-- legal block actually reached the chain.
--
-- Until now SperrvermerkOnchainSyncListener submitted a freeze (or threw and logged) and forgot: the outcome of a
-- submitted-but-reverted freeze was never read, holder_block.on_chain_freeze_tx_hash was never written, and a
-- standard or chain with no automated freeze path (SPL, Stellar, Starknet, Canton, confidential ERC-20) left the
-- register saying "blocked" while nothing on-chain was. The register-level block stays authoritative; this table
-- only tracks how far the chain follows it.
--
--   SUBMITTED             freeze transaction handed to the durable outbox, outcome not final yet
--   CONFIRMED             the freeze transaction is final and SUCCESS (the nightly job also reads isFrozen back)
--   FAILED                the freeze could not be submitted, reverted, or was replaced: the wallet may still move
--   UNSUPPORTED_ON_CHAIN  no automated, outcome-tracked freeze exists for this standard/chain: manual action needed
--   RELEASE_SUBMITTED / RELEASED / RELEASE_FAILED
--                         the same for the unfreeze that follows a lifted block (RELEASED also covers "nothing to
--                         release" and "another block still covers the wallet, the freeze stays")
CREATE TABLE holder_block_freeze (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_block_id  UUID         NOT NULL REFERENCES holder_block(id),
    deployment_id    UUID         NOT NULL REFERENCES asset_deployment(id),
    wallet_address   TEXT         NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    -- blockchain_transaction.id of the latest freeze/unfreeze transaction (that table is partitioned: no FK)
    tx_id            UUID,
    tx_hash          VARCHAR(128),
    detail           TEXT,
    attempts         INT          NOT NULL DEFAULT 0,
    -- how often the nightly read-back found the wallet NOT frozen although the freeze was CONFIRMED
    drift_count      INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at     TIMESTAMPTZ,
    verified_at      TIMESTAMPTZ,
    CONSTRAINT uq_holder_block_freeze UNIQUE (holder_block_id, deployment_id, wallet_address)
);
CREATE INDEX idx_holder_block_freeze_tx     ON holder_block_freeze (tx_id) WHERE tx_id IS NOT NULL;
CREATE INDEX idx_holder_block_freeze_open   ON holder_block_freeze (status)
    WHERE status IN ('SUBMITTED', 'FAILED', 'UNSUPPORTED_ON_CHAIN', 'RELEASE_SUBMITTED', 'RELEASE_FAILED');
CREATE INDEX idx_holder_block_freeze_deployment ON holder_block_freeze (deployment_id);

CREATE TABLE natural_person (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    given_name            TEXT NOT NULL,
    family_name           TEXT NOT NULL,
    date_of_birth         DATE,
    nationality           VARCHAR(2),
    country_of_residence  VARCHAR(2),
    tax_id                TEXT,
    tax_id_country        VARCHAR(2),
    address_line1         TEXT,
    address_line2         TEXT,
    city                  TEXT,
    postal_code           TEXT,
    country               VARCHAR(2),
    pep_status            VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    pep_status_updated_at TIMESTAMPTZ,
    redacted              BOOLEAN NOT NULL DEFAULT FALSE,
    redacted_at           TIMESTAMPTZ,
    redacted_by           UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE beneficial_owner (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID NOT NULL,
    natural_person_id UUID NOT NULL,
    ownership_pct     NUMERIC(5,2),
    control_type      VARCHAR(30) NOT NULL,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ceased_at         TIMESTAMPTZ,
    source            TEXT,
    verified_by       UUID,
    verified_at       TIMESTAMPTZ,
    notes             TEXT,
    verification_document_id UUID,
    fallback_reason TEXT,
    ceased_by UUID,
    cease_reason TEXT,
    cease_document_id UUID,
    UNIQUE (entity_id, natural_person_id, control_type, registered_at)
);
CREATE INDEX idx_beneficial_owner_entity ON beneficial_owner (entity_id) WHERE ceased_at IS NULL;
CREATE INDEX idx_beneficial_owner_person ON beneficial_owner (natural_person_id);

CREATE TABLE holder_identity (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_holder_id   UUID NOT NULL UNIQUE,
    legal_entity_id   UUID,
    natural_person_id UUID,
    CONSTRAINT chk_holder_identity_exactly_one CHECK (
        (legal_entity_id IS NOT NULL)::INT + (natural_person_id IS NOT NULL)::INT = 1
    )
);
CREATE INDEX idx_holder_identity_entity ON holder_identity (legal_entity_id);
CREATE INDEX idx_holder_identity_person ON holder_identity (natural_person_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- SANCTIONS SCREENING (GwG §10, AMLD6, MiCAR Art. 60)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE screening_run (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID,
    natural_person_id UUID,
    trigger_type      VARCHAR(30) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider          TEXT NOT NULL,
    lists_checked     TEXT[],
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    error_message     TEXT,
    initiated_by      UUID,
    -- Phase 6 / K7 (6-18, 6-19, 6-17 screening half): hit fingerprint + audited carry-forward of
    -- accepted false positives, PEP confirmation / EDD resolution path, per-run threshold and data version.
    -- Additive only. Existing hits get no fingerprint, so nothing already in the database can ever be
    -- carried forward silently: the first nightly run after deployment re-opens whatever is genuinely open.
    threshold_used NUMERIC(4,3),
    data_version   TEXT
);
CREATE INDEX idx_screening_run_entity  ON screening_run (entity_id)        WHERE entity_id IS NOT NULL;
CREATE INDEX idx_screening_run_person  ON screening_run (natural_person_id) WHERE natural_person_id IS NOT NULL;
CREATE INDEX idx_screening_run_status  ON screening_run (status)            WHERE status IN ('PENDING','HIT');
CREATE INDEX idx_screening_run_latest_entity ON screening_run (entity_id, provider, started_at DESC) WHERE entity_id IS NOT NULL;
CREATE INDEX idx_screening_run_latest_person ON screening_run (natural_person_id, provider, started_at DESC) WHERE natural_person_id IS NOT NULL;

CREATE TABLE screening_hit (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                   UUID NOT NULL REFERENCES screening_run(id),
    list_source              TEXT NOT NULL,
    category                 VARCHAR(20) NOT NULL DEFAULT 'SANCTIONS',
    matched_field            TEXT NOT NULL,
    matched_value            TEXT NOT NULL,
    match_score              NUMERIC(5,2),
    match_details            JSONB,
    accepted                 BOOLEAN,
    accepted_by              UUID,
    accepted_at              TIMESTAMPTZ,
    accept_reason            TEXT,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    external_id          TEXT,
    fingerprint          VARCHAR(64),
    carried_from_hit_id  UUID REFERENCES screening_hit(id),
    carried_at           TIMESTAMPTZ,
    resolution           VARCHAR(20),
    pep_confirmed_by     UUID,
    pep_confirmed_at     TIMESTAMPTZ,
    pep_confirm_note     TEXT,
    edd_approval_id      UUID,
    edd_approved_at      TIMESTAMPTZ,
    edd_review_due       TIMESTAMPTZ,
    CONSTRAINT chk_screening_hit_resolution
            CHECK (resolution IS NULL OR resolution IN ('FALSE_POSITIVE', 'CONFIRMED_PEP'))
);
CREATE INDEX idx_screening_hit_run     ON screening_hit (run_id);
CREATE INDEX idx_screening_hit_pending ON screening_hit (run_id)            WHERE accepted IS NULL;
CREATE INDEX idx_screening_hit_fingerprint ON screening_hit (fingerprint) WHERE fingerprint IS NOT NULL;

CREATE TABLE edd_approval (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    natural_person_id   UUID         NOT NULL REFERENCES natural_person(id),
    approved_by         UUID         NOT NULL,
    second_approver_id  UUID         NOT NULL,
    note                TEXT         NOT NULL,
    review_due          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_edd_two_people CHECK (approved_by <> second_approver_id)
);
CREATE INDEX idx_edd_approval_person ON edd_approval (natural_person_id, review_due DESC);

CREATE TABLE kyc_approval_record (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id           UUID         NOT NULL REFERENCES legal_entity(id),
    approved_by         UUID,
    second_approver_id  UUID,
    jurisdiction        VARCHAR(20)  NOT NULL,
    expiry_date         DATE         NOT NULL,
    checklist_compliant BOOLEAN      NOT NULL,
    override_note       TEXT,
    identified_pct      NUMERIC(6,2) NOT NULL DEFAULT 0,
    smo_fallback        BOOLEAN      NOT NULL DEFAULT FALSE,
    evidence_snapshot   TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_kyc_approval_record_entity ON kyc_approval_record (entity_id, created_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- TRAVEL RULE / TFR (Reg EU 2023/1113)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE travel_rule_message (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction            VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING_SEND',
    token_transfer_id    UUID,
    asset_id             UUID,
    originator_vasp_did  TEXT,
    beneficiary_vasp_did TEXT,
    originator_wallet    TEXT NOT NULL,
    beneficiary_wallet   TEXT NOT NULL,
    amount               NUMERIC(38,18),
    currency_symbol      TEXT,
    ivms101_payload      JSONB,
    protocol             TEXT,
    protocol_message_id  TEXT,
    sent_at              TIMESTAMPTZ,
    acknowledged_at      TIMESTAMPTZ,
    received_at          TIMESTAMPTZ,
    verified_at          TIMESTAMPTZ,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ── travel_rule_message: delivery bookkeeping, valuation, inbound matching ────────────────────
    attempts            INT          NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    valuation_source    TEXT,
    valuation_at        TIMESTAMPTZ,
    wallet_proof_id     UUID,
    payload_hash        VARCHAR(64),
    transfer_details    JSONB,
    matched_transfer_id UUID,
    matched_at          TIMESTAMPTZ,
    peer_vasp_id        TEXT
);
CREATE INDEX idx_trm_direction_status   ON travel_rule_message (direction, status);
CREATE INDEX idx_trm_transfer           ON travel_rule_message (token_transfer_id) WHERE token_transfer_id IS NOT NULL;
CREATE INDEX idx_trm_originator_wallet  ON travel_rule_message (originator_wallet);
CREATE INDEX idx_trm_beneficiary_wallet ON travel_rule_message (beneficiary_wallet);
CREATE INDEX idx_trm_open ON travel_rule_message (status, updated_at)
    WHERE status IN ('PENDING_SEND','FAILED','INCOMPLETE','CONFLICT','UNHOSTED_VERIFY_REQUIRED','INCOMPLETE_IVMS');
CREATE INDEX idx_trm_retry ON travel_rule_message (next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
CREATE INDEX idx_trm_inbound_unmatched ON travel_rule_message (created_at)
    WHERE direction = 'INBOUND' AND matched_transfer_id IS NULL;
CREATE UNIQUE INDEX uq_trm_inbound_peer_ref_hash
    ON travel_rule_message (originator_vasp_did, protocol_message_id, payload_hash)
    WHERE direction = 'INBOUND' AND protocol_message_id IS NOT NULL;

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

-- ═══════════════════════════════════════════════════════════════════════════
-- CORPORATE ACTIONS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE corporate_action (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                 UUID NOT NULL,
    action_type              VARCHAR(30) NOT NULL,
    status                   VARCHAR(30) NOT NULL DEFAULT 'ANNOUNCED',
    announcement_date        DATE,
    record_date              DATE,
    ex_date                  DATE,
    payment_date             DATE,
    ratio_numerator          NUMERIC(38,18),
    ratio_denominator        NUMERIC(38,18),
    amount_per_unit          NUMERIC(38,18),
    total_amount             NUMERIC(96,18),
    currency                 VARCHAR(3),
    coupon_payment_id        UUID,
    bond_period_start        DATE,
    bond_period_end          DATE,
    settlement_tx_hash       TEXT,
    settlement_chain         TEXT,
    settled_at               TIMESTAMPTZ,
    initiated_by             UUID NOT NULL,
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    notes                    TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Corporate actions: issuer proposal/attestation workflow.
    --
    -- Adds the issuer's half of the new cross-party settlement control (issuer attests the
    -- obligation/cash-leg is ready; the existing dual_control_approver_id/dual_control_approved_at
    -- columns are repurposed, unchanged in name, to mean "operator confirmation" — see
    -- CorporateAction's updated javadoc) and the PROPOSED/REJECTED states an issuer-initiated
    -- DIVIDEND/SPLIT/CALL proposal moves through before an operator approves it onto the register.
    --
    -- Also retires the PLEDGE action type (never read or written by any code path — the real
    -- pledge/collateral mechanism lives in the `lending` module) via a CHECK constraint.
    issuer_attested_by UUID,
    issuer_attested_at TIMESTAMPTZ,
    issuer_attestation_ref TEXT,
    snapshot_blocked_reason TEXT,
    -- K3b (Phase 3): corporate-action engine.
    --
    -- T3-06: entitlements are fixed as of the END of the record date. Chain-deployed assets net the
    -- indexed FINALIZED transfers up to that instant; off-chain register rows have no transfer
    -- history, so every change to a holder's position is now captured here by a trigger.
    -- T3-05: settled actions clear OVERDUE/MISSED/DEFAULTED signals; per-holder entitlements are
    -- rounded to the currency's minor unit and the difference is kept on the action.
    -- T3-02: SETTLED actions with unresolved nominee-pool (HELD_LOOK_THROUGH) entitlements are not
    -- closed; the flag makes them countable for the gauge and visible in the operator UI.
    rounding_residual NUMERIC(96,18),
    held_outstanding  BOOLEAN NOT NULL DEFAULT false,
    -- Wave 0b C6: the issuer attestation and the operator confirmation of a corporate action's payout must cover the
    -- COMPUTED amounts. Each is bound to a digest over (entries, total, rounding residual); the payout job only starts when
    -- both digests equal the digest of the entries as they are NOW, and a re-snapshot / recompute voids both sign-offs.
    payout_digest             VARCHAR(64),
    issuer_attested_digest    VARCHAR(64),
    operator_confirmed_digest VARCHAR(64),
    -- A COMPUTED action whose settlement the SYSTEM holds back (a Canton aggregate call that cannot exclude one holder,
    -- a finality hold, a frozen register) carries the reason here, so the maturity job can tell a registry-side hold from
    -- issuer non-payment and never turns the former into OVERDUE / DEFAULTED.
    settlement_hold_reason TEXT,
    CONSTRAINT ck_ca_action_type CHECK (action_type IN (
        'COUPON', 'DIVIDEND', 'SPLIT', 'REVERSE_SPLIT', 'CONVERSION',
        'REDEMPTION', 'PARTIAL_REDEMPTION', 'CALL', 'CAPITAL_CALL', 'INTEREST_PAYMENT'
    )),
    CONSTRAINT ck_ca_status CHECK (status IN (
        'PROPOSED', 'ANNOUNCED', 'SNAPSHOT_BLOCKED', 'RECORD_DATE_SET', 'COMPUTED', 'AWAITING_SETTLEMENT',
        'SETTLED', 'CLOSED', 'CANCELLED', 'REJECTED'
    ))
);
CREATE INDEX idx_ca_asset_status ON corporate_action (asset_id, status);
CREATE INDEX idx_ca_payment_date ON corporate_action (payment_date)
    WHERE status NOT IN ('SETTLED','CLOSED','CANCELLED');

-- The operator review queue's hot query: every PROPOSED row, across all assets.
CREATE INDEX idx_ca_proposed ON corporate_action (asset_id) WHERE status = 'PROPOSED';
COMMENT ON COLUMN corporate_action.payout_digest IS
    'SHA-256 over the computed entitlements (entries, total, rounding residual) - set when the action reaches COMPUTED.';
COMMENT ON COLUMN corporate_action.issuer_attested_digest IS
    'payout_digest the issuer attested (or the operator overrode). Settlement requires it to equal the current digest.';
COMMENT ON COLUMN corporate_action.operator_confirmed_digest IS
    'payout_digest the operator confirmed. Settlement requires it to equal the current digest.';
COMMENT ON COLUMN corporate_action.settlement_hold_reason IS
    'Set while the system itself holds the settlement back (not the issuer / operator); cleared when it is released.';

CREATE TABLE corporate_action_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    corporate_action_id UUID NOT NULL REFERENCES corporate_action(id),
    asset_holder_id     UUID NOT NULL,
    -- Denormalized from AssetHolder.investorId at snapshot time — lets the Steuerbescheinigung
    -- query "this investor's total income for tax year N" without a cross-module join.
    investor_id         UUID,
    wallet_address      TEXT NOT NULL,
    nominal_at_record   NUMERIC(96,18) NOT NULL,
    entitlement_amount  NUMERIC(96,18),
    settlement_tx_hash  TEXT,
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    payout_status VARCHAR(24) NOT NULL DEFAULT 'PAYABLE',
    -- Wave 0b H6: the payout gate pays eligible holders individually. A holder that fails the PartyEligibility gate at
    -- payout time (entity not ACTIVE, KYC missing/expired, unresolved sanctions hit, Sperrvermerk) is HELD_BLOCKED with
    -- the reason on the entry - recorded, excluded from the payable set, never paid, never silently dropped - instead of
    -- stalling every other holder's payout.
    held_reason TEXT,
    CONSTRAINT ck_ca_entry_payout_status
        CHECK (payout_status IN ('PAYABLE', 'HELD_LOOK_THROUGH', 'HELD_BLOCKED'))
);
CREATE INDEX idx_ca_entry_action ON corporate_action_entry (corporate_action_id);
CREATE INDEX idx_ca_entry_investor ON corporate_action_entry (investor_id);
CREATE INDEX idx_ca_entry_settled_at ON corporate_action_entry (settled_at) WHERE settled_at IS NOT NULL;
CREATE INDEX idx_ca_entry_holder ON corporate_action_entry (asset_holder_id);
COMMENT ON COLUMN corporate_action_entry.held_reason IS
    'Why this entry was not paid (HELD_BLOCKED): the PartyEligibility reasons at payout time.';

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN DRIFT DETECTION (eWpG §16 — DB is canonical)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE chain_drift_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id         UUID NOT NULL,
    deployment_id    UUID NOT NULL,
    chain_config_id  UUID,
    wallet_address   TEXT NOT NULL,
    db_balance       NUMERIC(96,18) NOT NULL,
    onchain_balance  NUMERIC(96,18) NOT NULL,
    severity         VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    detected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      UUID,
    resolution_notes TEXT,
    ict_incident_id  UUID,
    -- ChainDriftDetectionJob previously surfaced a registry-vs-chain balance mismatch to operators
    -- the moment it was first observed. Right after a fresh boot (or any restart), token_transfer
    -- can legitimately lag the registry by one detection cycle while Chaincache's durable event
    -- subscription is still catching up on a chain's history — every holder touched during that
    -- window looked like 100% drift. `confirmed` gates the two operator-visible surfaces (the
    -- registerwerk_chain_drift_open_total gauge and the "Open" queue) on a divergence having
    -- survived a second, independent detection run before anyone is asked to act on it.
    -- `first_detected_at` is the immutable counterpart to the already-refreshed `detected_at`, so a
    -- confirmed case still shows how long it has actually persisted.
    confirmed         BOOLEAN NOT NULL DEFAULT false,
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delta NUMERIC(96,18) GENERATED ALWAYS AS (onchain_balance - db_balance) STORED,
    -- P4-01: a holder whose deployment has no indexed rows at all used to be compared against its own
    -- register balance (COALESCE) and therefore never drifted. The drift job now records that case as
    -- an explicit NOT_INDEXED event instead of pretending the chain agrees with the register.
    kind VARCHAR(20) NOT NULL DEFAULT 'DRIFT',
    CONSTRAINT chk_drift_kind CHECK (kind IN ('DRIFT', 'NOT_INDEXED'))
);
CREATE INDEX idx_drift_asset_open    ON chain_drift_event (asset_id)   WHERE status = 'OPEN';
CREATE INDEX idx_drift_severity_open ON chain_drift_event (severity)   WHERE status = 'OPEN';
CREATE INDEX idx_drift_detected      ON chain_drift_event (detected_at);
CREATE INDEX idx_drift_confirmed_open ON chain_drift_event (status) WHERE status = 'OPEN' AND confirmed = true;

-- ═══════════════════════════════════════════════════════════════════════════
-- REGULATORY REPORTING
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE regreport_submission (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type            VARCHAR(30) NOT NULL,
    jurisdiction           VARCHAR(20) NOT NULL,
    -- Transport-only statuses. Regulatory reporting here is an opt-in, non-production draft
    -- generator: none of these states proves official-schema validity, filing, acceptance, or
    -- legal compliance. Earlier authority-outcome labels (ACCEPTED / ACKNOWLEDGED / REJECTED)
    -- described local gateway outcomes far too strongly.
    status                 VARCHAR(30) NOT NULL DEFAULT 'DRAFT_UNVALIDATED',
    reporting_period_start DATE NOT NULL,
    reporting_period_end   DATE NOT NULL,
    entity_id              UUID,
    asset_id               UUID,
    document_s3_key        TEXT,
    document_hash          BYTEA,
    document_signature     BYTEA,
    -- Legacy adapter-evidence columns. Retained so that historical rows keep their evidence;
    -- new code writes transported_at / transport_ref / transport_error instead.
    submitted_at           TIMESTAMPTZ,
    submission_ref         TEXT,
    acknowledged_at        TIMESTAMPTZ,
    rejection_reason       TEXT,
    transported_at         TIMESTAMPTZ,
    transport_ref          TEXT,
    transport_error        TEXT,
    generated_by           UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_regreport_type_period ON regreport_submission (report_type, reporting_period_end);
CREATE INDEX idx_regreport_entity      ON regreport_submission (entity_id) WHERE entity_id IS NOT NULL;
CREATE INDEX idx_regreport_status      ON regreport_submission (status)
    WHERE status IN ('DRAFT_UNVALIDATED', 'NOT_TRANSPORTED', 'TRANSPORT_FAILED',
                     'TRANSPORTED_UNVERIFIED');

-- ═══════════════════════════════════════════════════════════════════════════
-- DORA ICT INCIDENTS & THIRD-PARTY REGISTER (Art. 5-17, Art. 28)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE ict_incident (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category                VARCHAR(30) NOT NULL,
    severity                VARCHAR(20) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'DETECTED',
    title                   TEXT NOT NULL,
    description             TEXT,
    source_event_type       TEXT,
    source_event_ref        UUID,
    detected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- DORA Art. 19(4) / RTS (EU) 2025/301 impose *two* deadlines. Tracking only the 24h
    -- initial-report clock let an incident look "on track" for up to 20h after the stricter
    -- 4h-from-classification deadline had already passed.
    classified_at           TIMESTAMPTZ,
    classification_deadline TIMESTAMPTZ,
    initial_report_deadline TIMESTAMPTZ,
    final_report_deadline   TIMESTAMPTZ,
    initial_reported_at     TIMESTAMPTZ,
    final_reported_at       TIMESTAMPTZ,
    -- Actor responsible for the Art. 19 authority-notification decision.
    reported_by             UUID,
    authority_ref           TEXT,
    contained_at            TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    root_cause              TEXT,
    remediation_steps       TEXT,
    assigned_to             UUID,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    --  * awareness_at: the anchor for the 24 h / 1 month clocks (operator-entered, immutable)
    --  * classification evidence + downgrade marker + intermediate (72 h) report tracking
    --  * ict_incident_report: append-only authority submissions (initial / intermediate / final)
    --  * guard trigger: awareness and the first-submission columns are write-once
    --  * ict_incident_alert: one row per (incident, breach type) so overdue alerts are not re-sent every run
    awareness_at                  TIMESTAMPTZ NOT NULL,
    classification_reason         TEXT,
    classification_criteria       JSONB,
    classified_by                 UUID,
    classification_pending        BOOLEAN NOT NULL DEFAULT FALSE,
    intermediate_report_deadline  TIMESTAMPTZ,
    intermediate_reported_at      TIMESTAMPTZ,
    downgraded_at                 TIMESTAMPTZ,
    downgrade_reason              TEXT
);
CREATE INDEX idx_ict_incident_deadline ON ict_incident (initial_report_deadline)
    WHERE initial_reported_at IS NULL;
CREATE INDEX idx_ict_incident_severity ON ict_incident (severity, detected_at);
CREATE INDEX idx_ict_incident_open ON ict_incident (status) WHERE status <> 'CLOSED';

CREATE TABLE ict_incident_report (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   UUID NOT NULL REFERENCES ict_incident (id),
    report_type   VARCHAR(20) NOT NULL CHECK (report_type IN ('INITIAL', 'INTERMEDIATE', 'FINAL')),
    submitted_at  TIMESTAMPTZ NOT NULL,
    authority_ref TEXT,
    submitted_by  UUID,
    note          TEXT,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ict_incident_report_incident ON ict_incident_report (incident_id, submitted_at);

CREATE OR REPLACE FUNCTION ict_incident_report_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ict_incident_report is append-only (% refused)', TG_OP;
END $$;

CREATE TRIGGER trg_ict_incident_report_immutable
    BEFORE UPDATE OR DELETE ON ict_incident_report
    FOR EACH ROW EXECUTE FUNCTION ict_incident_report_immutable();

CREATE OR REPLACE FUNCTION ict_incident_write_once()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.awareness_at IS DISTINCT FROM OLD.awareness_at THEN
        RAISE EXCEPTION 'ict_incident.awareness_at is immutable';
    END IF;
    IF OLD.initial_reported_at IS NOT NULL AND NEW.initial_reported_at IS DISTINCT FROM OLD.initial_reported_at THEN
        RAISE EXCEPTION 'ict_incident.initial_reported_at is write-once';
    END IF;
    IF OLD.intermediate_reported_at IS NOT NULL AND NEW.intermediate_reported_at IS DISTINCT FROM OLD.intermediate_reported_at THEN
        RAISE EXCEPTION 'ict_incident.intermediate_reported_at is write-once';
    END IF;
    IF OLD.final_reported_at IS NOT NULL AND NEW.final_reported_at IS DISTINCT FROM OLD.final_reported_at THEN
        RAISE EXCEPTION 'ict_incident.final_reported_at is write-once';
    END IF;
    IF OLD.authority_ref IS NOT NULL AND NEW.authority_ref IS DISTINCT FROM OLD.authority_ref THEN
        RAISE EXCEPTION 'ict_incident.authority_ref is write-once';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_ict_incident_write_once
    BEFORE UPDATE ON ict_incident
    FOR EACH ROW EXECUTE FUNCTION ict_incident_write_once();

CREATE TABLE ict_incident_alert (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES ict_incident (id),
    breach_type VARCHAR(30) NOT NULL,
    alerted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (incident_id, breach_type)
);

CREATE TABLE third_party_provider (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL,
    category                VARCHAR(30) NOT NULL,
    criticality             VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    lei                     TEXT,
    country                 VARCHAR(2),
    contract_start          DATE,
    contract_end            DATE,
    sub_outsourcing         BOOLEAN NOT NULL DEFAULT FALSE,
    sub_outsourcing_details TEXT,
    primary_contact         TEXT,
    sla_availability_pct    NUMERIC(5,2),
    rto_hours               INTEGER,
    rpo_hours               INTEGER,
    notified_authority      BOOLEAN NOT NULL DEFAULT FALSE,
    notified_at             TIMESTAMPTZ,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════════════════
-- AUDIT LOG — tamper-evident hash chain (eWpRV §6)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE audit_event (
    id           UUID    NOT NULL DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100) NOT NULL,
    subject_type VARCHAR(50)  NOT NULL,
    subject_id   UUID    NOT NULL,
    actor_id     UUID,
    actor_role   VARCHAR(64),
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sequence_no  BIGINT  DEFAULT nextval('audit_event_seq'),
    prev_hash    BYTEA,
    entry_hash   BYTEA,
    entry_sig    BYTEA,
    reverses_event_id UUID,
    correlation_id    UUID,
    -- Per-row canonical-envelope version (1 = legacy eventType/subject/payload only, 2 = + actor, role,
    -- occurred_at, correlation, reversal). The verifier selects the algorithm by this column.
    canon_version SMALLINT NOT NULL DEFAULT 1,
    -- Operational insert time (event time is occurred_at, captured at publish).
    recorded_at TIMESTAMPTZ
) PARTITION BY RANGE (occurred_at);
COMMENT ON COLUMN audit_event.reverses_event_id IS
    'audit_event.id of the entry this one reverses/corrects, when this row records a '
    'correction (e.g. a compensating force-burn undoing a wrongful mint). No FK by design '
    '(see audit_event: no FK constraints for throughput) — resolved at the application layer.';
COMMENT ON COLUMN audit_event.correlation_id IS
    'Free-form grouping id for audit entries that belong to one logical operation '
    '(e.g. a batch of forced transfers submitted together).';
CREATE INDEX idx_audit_subject    ON audit_event (subject_type, subject_id);
CREATE INDEX idx_audit_actor      ON audit_event (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_event_type ON audit_event (event_type);
CREATE INDEX idx_audit_payload    ON audit_event USING GIN (payload);
CREATE INDEX idx_audit_event_seq  ON audit_event (sequence_no);
CREATE INDEX idx_audit_reverses    ON audit_event (reverses_event_id) WHERE reverses_event_id IS NOT NULL;
CREATE INDEX idx_audit_correlation ON audit_event (correlation_id)    WHERE correlation_id    IS NOT NULL;

-- WORM trigger: UPDATE and DELETE are forbidden even by the table owner (eWpRV §6).
CREATE OR REPLACE FUNCTION audit_event_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable (eWpRV §6). Operation: %', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();

-- Statement-level TRUNCATE guard (the row-level WORM trigger does not fire on TRUNCATE).
CREATE OR REPLACE FUNCTION audit_event_no_truncate()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event must not be truncated (eWpRV §6).';
END;
$$;

-- Monthly partition auto-creation — also called by AuditPartitionJob.
CREATE OR REPLACE FUNCTION audit_event_ensure_partitions(months_ahead INT DEFAULT 6)
    RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 60 THEN
        RAISE EXCEPTION 'audit_event_ensure_partitions: months_ahead must be between 0 and 60';
    END IF;
    FOR i IN 0..months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := 'audit_event_' || to_char(month_start, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
                part_name, month_start, month_end);
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM registerwerk_app', part_name);
            EXECUTE format('CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON %I '
                           'FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate()', part_name);
        END IF;
    END LOOP;
END;
$$;

-- Default partition catches anything outside the created range
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

-- TRUNCATE is statement-level and is not inherited by partitions: guard the parent and the default partition
-- here (audit_event_ensure_partitions guards every partition it creates).
CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate();
CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON audit_event_default
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate();

-- Bootstrap 12 months of partitions
SELECT audit_event_ensure_partitions(12);

CREATE OR REPLACE FUNCTION rw_ensure_monthly_partitions(
    p_table        regclass,
    p_time_column  text,
    p_months_ahead int DEFAULT 6
) RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    schema_name TEXT;
    bare_name   TEXT;
    actual_col  TEXT;
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    SELECT n.nspname, c.relname INTO schema_name, bare_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_table;

    IF bare_name LIKE 'audit\_%' THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: audit tables are managed by audit_event_ensure_partitions()';
    END IF;
    IF p_months_ahead IS NULL OR p_months_ahead < 0 OR p_months_ahead > 60 THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: p_months_ahead must be between 0 and 60';
    END IF;

    SELECT a.attname INTO actual_col
    FROM pg_partitioned_table pt
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = p_table;

    IF actual_col IS NULL THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is not a partitioned table', p_table;
    ELSIF actual_col IS DISTINCT FROM p_time_column THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is partitioned on column % not %',
            p_table, actual_col, p_time_column;
    END IF;

    FOR i IN 0..p_months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := bare_name || '_' || to_char(month_start, 'YYYY_MM');
        BEGIN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                schema_name, part_name, p_table::text, month_start, month_end
            );
        EXCEPTION WHEN duplicate_table THEN
            NULL;
        END;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION rw_retire_partitions(
    p_table       regclass,
    p_time_column text,
    p_keep_months int,
    p_mode        text DEFAULT 'DETACH'
) RETURNS SETOF text LANGUAGE plpgsql AS $$
DECLARE
    schema_name TEXT;
    bare_name   TEXT;
    actual_col  TEXT;
    cutoff      DATE;
    child       RECORD;
    part_month  DATE;
    new_name    TEXT;
BEGIN
    IF p_mode <> 'DETACH' THEN
        RAISE EXCEPTION 'rw_retire_partitions: mode % is not supported; only DETACH is allowed', p_mode;
    END IF;

    SELECT n.nspname, c.relname INTO schema_name, bare_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_table;

    SELECT a.attname INTO actual_col
    FROM pg_partitioned_table pt
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = p_table;

    IF actual_col IS NULL THEN
        RAISE EXCEPTION 'rw_retire_partitions: % is not a partitioned table', p_table;
    ELSIF actual_col IS DISTINCT FROM p_time_column THEN
        RAISE EXCEPTION 'rw_retire_partitions: % is partitioned on column % not %',
            p_table, actual_col, p_time_column;
    END IF;

    cutoff := date_trunc('month', now())::DATE - (p_keep_months || ' months')::INTERVAL;

    FOR child IN
        SELECT ch.relname AS child_name, chn.nspname AS child_schema
        FROM pg_inherits i
        JOIN pg_class ch ON ch.oid = i.inhrelid
        JOIN pg_namespace chn ON chn.oid = ch.relnamespace
        WHERE i.inhparent = p_table
          AND ch.relname ~ (bare_name || '_[0-9]{4}_[0-9]{2}$')
        ORDER BY ch.relname
    LOOP
        part_month := to_date(substring(child.child_name FROM '([0-9]{4}_[0-9]{2})$'), 'YYYY_MM');
        IF part_month + INTERVAL '1 month' <= cutoff THEN
            new_name := bare_name || '_archived_' || to_char(part_month, 'YYYY_MM');
            EXECUTE format('ALTER TABLE %s DETACH PARTITION %I.%I',
                p_table::text, child.child_schema, child.child_name);
            EXECUTE format('ALTER TABLE %I.%I RENAME TO %I',
                child.child_schema, child.child_name, new_name);
            RETURN NEXT new_name;
        END IF;
    END LOOP;
    RETURN;
END;
$$;

SELECT rw_ensure_monthly_partitions('token_transfer', 'occurred_at', 6);

SELECT rw_ensure_monthly_partitions('blockchain_transaction', 'created_at', 6);

-- Single-row pointer to the most recent audit_event.entry_hash. AuditEventRecorder locks
-- this row with SELECT ... FOR UPDATE for the duration of its append transaction, serializing
-- hash-chain appends across threads and backend instances so the chain can never fork under
-- concurrent/multi-instance load.
CREATE TABLE audit_chain_tip (
    id         BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    entry_hash BYTEA,
    -- Tip: sequence + last update time next to the hash (sequence NULL until the next append).
    sequence_no BIGINT,
    updated_at TIMESTAMPTZ
);
COMMENT ON TABLE audit_chain_tip IS
    'Single-row pointer to the most recent audit_event.entry_hash. AuditEventRecorder '
    'locks this row with SELECT ... FOR UPDATE for the duration of its append transaction, '
    'serializing hash-chain appends across threads and backend instances so the chain '
    'can never fork under concurrent/multi-instance load.';

-- Signing watermark: first sequence_no appended while signing was enabled. Write-once.
CREATE TABLE audit_chain_meta (
    id              BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    signing_from_seq BIGINT
);

INSERT INTO audit_chain_meta (id, signing_from_seq) VALUES (TRUE, NULL);

CREATE OR REPLACE FUNCTION audit_meta_write_once()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.signing_from_seq IS NOT NULL THEN
        RAISE EXCEPTION 'audit_chain_meta.signing_from_seq is write-once. Operation: %', TG_OP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_chain_meta_write_once
    BEFORE UPDATE OR DELETE ON audit_chain_meta
    FOR EACH ROW EXECUTE FUNCTION audit_meta_write_once();

-- Signed anchors of the chain tip (DAILY) and archive markers (ARCHIVED_UP_TO).
CREATE TABLE audit_chain_anchor (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('DAILY', 'ARCHIVED_UP_TO')),
    anchor_date DATE NOT NULL,
    sequence_no BIGINT NOT NULL,
    entry_hash  BYTEA NOT NULL,
    sig         BYTEA,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, anchor_date)
);

CREATE OR REPLACE FUNCTION audit_anchor_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_chain_anchor rows are immutable. Operation: %', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_chain_anchor_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_anchor
    FOR EACH ROW EXECUTE FUNCTION audit_anchor_immutable();

-- Poison audit publications moved out of the retry loop (never retried forever, never silently dropped).
CREATE TABLE audit_event_dead_letter (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id   UUID NOT NULL,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    attempts         INTEGER NOT NULL,
    moved_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 7A-05: dedup key for audit appends. The partitioned audit_event cannot carry a global unique key
-- without occurred_at, so each AuditRecord's recordId is claimed here in the same transaction as the append.
-- Not WORM: rows are pruned after 30 days by AuditRecordIdPruneJob.
CREATE TABLE audit_event_record_id (
    record_id   UUID PRIMARY KEY,
    sequence_no BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_event_record_id_recorded_at ON audit_event_record_id (recorded_at);

-- 7B-04: persisted verdict of every chain verification run (insert-only).
CREATE TABLE audit_chain_verification (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ran_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid            BOOLEAN NOT NULL,
    rows_checked     BIGINT NOT NULL,
    first_broken_seq BIGINT,
    reason           TEXT,
    source           VARCHAR(20) NOT NULL CHECK (source IN ('NIGHTLY', 'ON_DEMAND'))
);
CREATE INDEX idx_audit_chain_verification_ran_at ON audit_chain_verification (ran_at DESC);

-- Dual-control acknowledgement of a broken verdict.
CREATE TABLE audit_chain_verification_ack (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    verification_id UUID NOT NULL UNIQUE REFERENCES audit_chain_verification (id),
    acked_by        UUID,
    approver_id     UUID,
    note            TEXT,
    acked_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION audit_verification_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable. Operation: %', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_chain_verification_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_verification
    FOR EACH ROW EXECUTE FUNCTION audit_verification_immutable();

CREATE TRIGGER trg_audit_chain_verification_ack_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_verification_ack
    FOR EACH ROW EXECUTE FUNCTION audit_verification_immutable();

INSERT INTO audit_chain_tip (id, entry_hash) VALUES (TRUE, NULL);

-- ═══════════════════════════════════════════════════════════════════════════
-- SPRING MODULITH — event publication outbox
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE event_publication (
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INTEGER,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);
CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);

-- ═══════════════════════════════════════════════════════════════════════════
-- MiCA CASP AUTHORIZATION REGISTER
-- ═══════════════════════════════════════════════════════════════════════════

-- Counterparty CASP authorization register (MiCA Reg (EU) 2023/1114).
-- The EU-wide transitional period ends on 1 July 2026 (ESMA statement, 17 Apr 2026);
-- from that date, transfers to CASPs without MiCA authorization must not be executed.
-- Records are maintained by compliance officers from the ESMA / NCA registers.
CREATE TABLE casp_authorization (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vasp_did          VARCHAR(255) NOT NULL UNIQUE,
    legal_name        TEXT NOT NULL,
    lei               VARCHAR(20),
    home_member_state VARCHAR(2),
    status            VARCHAR(30) NOT NULL,
    authorization_id  TEXT,
    valid_from        DATE,
    valid_until       DATE,
    source            TEXT,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by          UUID,
    second_approver_id   UUID,
    country              VARCHAR(2)
);
CREATE INDEX idx_casp_authorization_status ON casp_authorization (status);
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

-- ── Register statement records (§19 eWpG) ─────────────────────────────────────
CREATE TABLE register_statement (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holder_id        UUID NOT NULL REFERENCES asset_holder(id),
    asset_id         UUID NOT NULL REFERENCES asset(id),
    investor_id      UUID NOT NULL REFERENCES legal_entity(id),
    -- Trigger: INITIAL_ENTRY, CHANGE, ANNUAL, ON_DEMAND (§19(1)).
    trigger          VARCHAR(20) NOT NULL,
    -- Snapshot of register content at issuance time (the statement is a record).
    nominal_amount   NUMERIC(96,18) NOT NULL,
    wallet_address   VARCHAR(66),
    holder_reference VARCHAR(64),
    content_hash     VARCHAR(66) NOT NULL,
    -- keccak/sha-256 of the rendered PDF
    pdf_document_id  UUID,
    -- reference into the document store
    -- Delivery status of the text-form statement (§19: "in Textform").
    delivery_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivery_channel VARCHAR(20),
    delivery_error   TEXT,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    delivery_error_code VARCHAR(40)
);
CREATE INDEX idx_register_statement_holder ON register_statement (holder_id);
CREATE INDEX idx_register_statement_investor ON register_statement (investor_id);
CREATE INDEX idx_register_statement_issued ON register_statement (issued_at);
CREATE INDEX idx_register_statement_delivery ON register_statement (delivery_status)
    WHERE delivery_status IN ('PENDING', 'FAILED');

-- ═══════════════════════════════════════════════════════════════════════════
-- REGISTER INSPECTION (§10 eWpG) & REGISTER TRANSFER (§§21/22 eWpG, §20 eWpRV)
-- ═══════════════════════════════════════════════════════════════════════════

-- §10 eWpG grants electronic inspection rights to participants: the issuer, the
-- holder, and — in single entry — anyone in whose favour a right is recorded. A
-- Berechtigter always has a legitimate interest (§10(2) eWpRV). Other applicants
-- must demonstrate a legitimate interest, which the operator reviews.
--
-- §§21/22 eWpG require the operator to be able to hand the register over to a
-- successor (e.g. when it can no longer meet the statutory requirements). §20
-- eWpRV requires the procedure and the data transfer to be documented. The
-- on-chain control handover already exists in the contracts; this records the
-- off-chain export and its status.

-- ── Register inspection requests (§10 eWpG) ───────────────────────────────────
CREATE TABLE register_inspection_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    -- The applicant; may be an onboarded entity or an external party.
    requester_entity_id UUID REFERENCES legal_entity(id),
    requester_name      TEXT NOT NULL,
    requester_email     TEXT,
    -- Asserted basis: ISSUER, HOLDER, BENEFICIARY (always legitimate, §10(2)
    -- eWpRV) or LEGITIMATE_INTEREST (reviewed by the operator).
    legal_basis         VARCHAR(30) NOT NULL,
    stated_interest     TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    -- REQUESTED, APPROVED, REJECTED, FULFILLED.
    decision_reason     TEXT,
    decided_by          UUID REFERENCES legal_entity(id),
    decided_at          TIMESTAMPTZ,
    fulfilled_at        TIMESTAMPTZ,
    content_hash        VARCHAR(66),
    -- hash of the disclosed extract, when fulfilled
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- FALSE (the honest default) means the claimed inspection right was not verified: such a request
    -- was auto-approved on a self-declared basis.
    claim_verified BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_inspection_asset ON register_inspection_request (asset_id);
CREATE INDEX idx_inspection_status ON register_inspection_request (status)
    WHERE status IN ('REQUESTED', 'APPROVED');

-- ── Register transfer (§§21/22 eWpG, §20 eWpRV) ───────────────────────────────
CREATE TABLE register_transfer (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id              UUID NOT NULL REFERENCES asset(id),
    -- Name / identifier of the successor registry operator.
    successor_name        TEXT NOT NULL,
    successor_identifier  TEXT,
    -- Reason for the handover (§22: e.g. operator can no longer meet requirements).
    reason                TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, EXPORTED, HANDED_OVER, COMPLETED, CANCELLED.
    -- The exported data package (the §20 eWpRV data transfer) and its hash.
    export_hash           VARCHAR(66),
    export_manifest       JSONB,
    -- On-chain control handover reference (links to the contract two-step handover).
    onchain_tx_hash       VARCHAR(66),
    initiated_by          UUID REFERENCES legal_entity(id),
    initiated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at           TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    previous_asset_status    VARCHAR(30),
    -- Hash over the register content only (no exportedAt envelope), re-checked at completion.
    register_content_hash    VARCHAR(66),
    -- Successor's on-chain registry/owner address, verified against each EVM deployment at handover.
    successor_onchain_address VARCHAR(42),
    -- Per-deployment handover records: [{deploymentId, chain, txHash, verified, method, ...}]
    onchain_handovers        JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX idx_register_transfer_asset ON register_transfer (asset_id);
CREATE INDEX idx_register_transfer_status ON register_transfer (status)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');

-- ═══════════════════════════════════════════════════════════════════════════
-- DSGVO ART. 17 ERASURE REQUESTS
-- ═══════════════════════════════════════════════════════════════════════════

-- Persisted operator work items for the Art. 12(3) response clock and resolution.
-- Actual erasure is reviewed because statutory retention may apply.
CREATE TABLE erasure_request (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id            UUID           NOT NULL REFERENCES legal_entity(id),
    requested_by_user_id UUID,
    status               VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
    requested_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    due_at               TIMESTAMPTZ    NOT NULL,
    reviewed_by          UUID,
    reviewed_at          TIMESTAMPTZ,
    resolution_note      TEXT,
    retained_notice_channel TEXT,
    -- DSAR erasure: list what was erased, retained (with legal basis) and not covered; COMPLETED_PARTIAL
    -- states that categories of personal data were deliberately not touched by the routine.
    resolution_detail TEXT,
    CONSTRAINT chk_erasure_request_status
        CHECK (status IN ('REQUESTED', 'IN_REVIEW', 'COMPLETED', 'COMPLETED_PARTIAL', 'REJECTED'))
);

-- Operator queue: open requests, oldest first.
CREATE INDEX idx_erasure_request_open ON erasure_request (requested_at)
    WHERE status IN ('REQUESTED','IN_REVIEW');

-- Dedup lookup for repeated submissions by the same entity.
CREATE INDEX idx_erasure_request_entity ON erasure_request (entity_id, status);

-- ═══════════════════════════════════════════════════════════════════════════
-- ONCHAIN ORGANIZATION IDENTITY (ecosystem OrgRegistry mirror)
-- ═══════════════════════════════════════════════════════════════════════════

-- Every participant wallet belongs to exactly one organization per chain (SWIAT-style
-- "every user belongs to a company"). The org's onchain anchor is its ONCHAINID
-- (onchain_identity table); these tables mirror the OrgRegistry / PermissionRegistry
-- contracts so the backend can serve reads without RPC round-trips. The onchain state
-- is authoritative; a reconciliation job flags drift.
CREATE TABLE org_registration (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id   UUID           NOT NULL REFERENCES legal_entity(id),
    chain_config_id   UUID           NOT NULL REFERENCES chain_config(id),
    org_address       VARCHAR(66)    NOT NULL,
    -- ISO-3166-1 numeric; kept so a deferred/retried registerOrg broadcast carries it
    country_code      SMALLINT,
    status            VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    registered_tx     VARCHAR(66),
    suspended_at      TIMESTAMPTZ,
    suspended_by      UUID,
    suspension_reason TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- Domain rows must retain the exact block incarnation that last moved them into an
    -- on-chain-confirmed state.  A status alone (ACTIVE/CONFIRMED) is not a causal token: after a
    -- reorg, the same transaction may be re-mined in a different block and confirm the same row
    -- again.  Compensating the older incarnation must not undo that newer canonical confirmation.
    confirmed_block_number BIGINT,
    confirmed_block_hash VARCHAR(128),
    status_tx VARCHAR(66),
    status_chain_config_id UUID REFERENCES chain_config(id),
    status_block_number BIGINT,
    status_block_hash VARCHAR(128),
    status_requested_at TIMESTAMPTZ,
    CONSTRAINT uq_org_registration_entity_chain UNIQUE (legal_entity_id, chain_config_id),
    CONSTRAINT chk_org_registration_status CHECK (
        status IN ('PENDING','ACTIVE','SUSPEND_PENDING','SUSPENDED','SUSPEND_FAILED',
                   'REINSTATE_PENDING','REINSTATE_FAILED','FAILED')
    ),
    CONSTRAINT chk_org_status_causality_complete CHECK (
        (status_chain_config_id IS NULL AND status_block_number IS NULL AND status_block_hash IS NULL)
        OR
        (status_tx IS NOT NULL AND status_chain_config_id IS NOT NULL
            AND status_block_number IS NOT NULL AND status_block_hash IS NOT NULL)
    ),
    CONSTRAINT chk_org_status_terminal_provenance CHECK (
        status NOT IN ('SUSPENDED','SUSPEND_FAILED','REINSTATE_FAILED')
        OR (status_tx IS NOT NULL AND status_chain_config_id IS NOT NULL
            AND status_block_number IS NOT NULL AND status_block_hash IS NOT NULL)
    )
);
CREATE INDEX idx_org_registration_status ON org_registration (status);

CREATE TABLE org_member_wallet (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_registration_id UUID           NOT NULL REFERENCES org_registration(id),
    chain_config_id     UUID           NOT NULL REFERENCES chain_config(id),
    wallet_address      VARCHAR(66)    NOT NULL,
    app_user_id         UUID,
    label               VARCHAR(120),
    roles               TEXT[]         NOT NULL DEFAULT '{}',
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    bound_tx            VARCHAR(66),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    removed_at          TIMESTAMPTZ,
    bound_block_number BIGINT,
    bound_block_hash VARCHAR(128),
    removed_tx VARCHAR(66),
    removed_chain_config_id UUID REFERENCES chain_config(id),
    removed_block_number BIGINT,
    removed_block_hash VARCHAR(128),
    CONSTRAINT chk_org_member_wallet_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVAL_PENDING','REMOVED','REMOVAL_FAILED','FAILED')
    ),
    CONSTRAINT chk_member_removal_causality_complete CHECK (
        (removed_chain_config_id IS NULL AND removed_block_number IS NULL AND removed_block_hash IS NULL)
        OR
        (removed_tx IS NOT NULL AND removed_chain_config_id IS NOT NULL
            AND removed_block_number IS NOT NULL AND removed_block_hash IS NOT NULL)
    ),
    CONSTRAINT chk_member_removal_terminal_provenance CHECK (
        status NOT IN ('REMOVED','REMOVAL_FAILED')
        OR (removed_tx IS NOT NULL AND removed_chain_config_id IS NOT NULL
            AND removed_block_number IS NOT NULL AND removed_block_hash IS NOT NULL)
    )
);

-- One live binding per wallet per chain (the OrgRegistry enforces the same onchain).
CREATE UNIQUE INDEX uq_org_member_wallet_live
    ON org_member_wallet (chain_config_id, lower(wallet_address))
    WHERE status IN ('PENDING','ACTIVE');
CREATE INDEX idx_org_member_wallet_org ON org_member_wallet (org_registration_id, status);

-- Short-lived nonce challenges proving control of a wallet before binding (EIP-191).
CREATE TABLE wallet_bind_challenge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_entity_id UUID        NOT NULL REFERENCES legal_entity(id),
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    wallet_address  VARCHAR(66) NOT NULL,
    nonce           VARCHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallet_bind_challenge_lookup
    ON wallet_bind_challenge (legal_entity_id, chain_config_id, lower(wallet_address))
    WHERE used_at IS NULL;

-- Permission framework (PermissionRegistry mirror).
CREATE TABLE permission_definition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(120) NOT NULL UNIQUE,
    permission_hash VARCHAR(66)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    dapp_listing_id UUID,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    defined_tx      VARCHAR(66),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_permission_definition_status CHECK (
        status IN ('DRAFT','ACTIVE','RETIRED')
    )
);

CREATE TABLE permission_grant (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_definition_id UUID        NOT NULL REFERENCES permission_definition(id),
    org_registration_id      UUID        NOT NULL REFERENCES org_registration(id),
    grant_type               VARCHAR(10) NOT NULL,
    role_code                VARCHAR(120),
    -- meaningful on ORG grants: when true, members additionally need a delegated role
    role_restricted          BOOLEAN     NOT NULL DEFAULT false,
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    granted_tx               VARCHAR(66),
    revoked_tx               VARCHAR(66),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at               TIMESTAMPTZ,
    -- 4-eyes: operator-tier permission grants/revocations carry step-up *and* a second
    -- approver, matching the asset_token_admin_grant pattern.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    granted_chain_config_id UUID REFERENCES chain_config(id),
    granted_block_number BIGINT,
    granted_block_hash VARCHAR(128),
    revoked_chain_config_id UUID REFERENCES chain_config(id),
    revoked_block_number BIGINT,
    revoked_block_hash VARCHAR(128),
    -- uq_org_member_wallet_live (defined above, at table creation) already excludes a retiring
    -- predecessor so LIFO reorg compensation can represent both the replacement binding and the
    -- predecessor removal as pending. MemberWalletService still rejects a fresh bind while a
    -- predecessor removal is unresolved.
    role_restriction_status VARCHAR(20) NOT NULL DEFAULT 'STABLE',
    requested_role_restricted BOOLEAN,
    role_restriction_tx VARCHAR(66),
    role_restriction_chain_config_id UUID REFERENCES chain_config(id),
    role_restriction_block_number BIGINT,
    role_restriction_block_hash VARCHAR(128),
    role_restriction_requested_at TIMESTAMPTZ,
    CONSTRAINT chk_permission_grant_type CHECK (grant_type IN ('ORG','ROLE')),
    CONSTRAINT chk_permission_grant_role CHECK (
        (grant_type = 'ROLE') = (role_code IS NOT NULL)
    ),
    CONSTRAINT chk_permission_grant_status CHECK (
        status IN ('PENDING','ACTIVE','REVOCATION_PENDING','REVOKED','REVOCATION_FAILED','FAILED')
    ),
    CONSTRAINT chk_permission_grant_revocation_provenance CHECK (
        (status = 'REVOKED') =
        (revoked_chain_config_id IS NOT NULL
            AND revoked_block_number IS NOT NULL
            AND revoked_block_hash IS NOT NULL
            AND revoked_tx IS NOT NULL)
    ),
    CONSTRAINT chk_role_restriction_status CHECK (
        role_restriction_status IN ('STABLE','CHANGE_PENDING','CHANGE_FAILED')
    ),
    CONSTRAINT chk_role_restriction_request CHECK (
        (role_restriction_status = 'STABLE' AND requested_role_restricted IS NULL)
        OR (role_restriction_status IN ('CHANGE_PENDING','CHANGE_FAILED')
            AND requested_role_restricted IS NOT NULL AND role_restriction_requested_at IS NOT NULL)
    ),
    CONSTRAINT chk_role_restriction_causality_complete CHECK (
        (role_restriction_chain_config_id IS NULL AND role_restriction_block_number IS NULL
            AND role_restriction_block_hash IS NULL)
        OR
        (role_restriction_tx IS NOT NULL AND role_restriction_chain_config_id IS NOT NULL
            AND role_restriction_block_number IS NOT NULL AND role_restriction_block_hash IS NOT NULL)
    ),
    CONSTRAINT chk_role_restriction_failed_provenance CHECK (
        role_restriction_status <> 'CHANGE_FAILED'
        OR (role_restriction_tx IS NOT NULL AND role_restriction_chain_config_id IS NOT NULL
            AND role_restriction_block_number IS NOT NULL AND role_restriction_block_hash IS NOT NULL)
    )
);
CREATE INDEX idx_permission_grant_org ON permission_grant (org_registration_id, status);
CREATE INDEX idx_permission_grant_definition ON permission_grant (permission_definition_id, status);

-- uq_ecosystem_trusted_issuer_live (defined above, at table creation) already excludes retiring
-- predecessors: after a confirmed removal and replacement, a suffix reorg must be able to return
-- both the replacement addition and the predecessor removal to pending during LIFO compensation.
-- Application-level lifecycle checks still reject a fresh addition while a predecessor removal is
-- unresolved.

-- Service checks give a useful error; these indexes also close the concurrent-request race.
CREATE UNIQUE INDEX uq_permission_grant_live_org
    ON permission_grant (permission_definition_id, org_registration_id)
    WHERE grant_type = 'ORG'
      AND status IN ('PENDING','ACTIVE');
CREATE UNIQUE INDEX uq_permission_grant_live_role
    ON permission_grant (permission_definition_id, org_registration_id, role_code)
    WHERE grant_type = 'ROLE'
      AND status IN ('PENDING','ACTIVE');
CREATE INDEX idx_permission_grant_role_restriction_pending
    ON permission_grant (role_restriction_status)
    WHERE role_restriction_status = 'CHANGE_PENDING';

-- Ecosystem-wide trusted claim issuers (EcosystemTrustedIssuersRegistry mirror).
CREATE TABLE ecosystem_trusted_issuer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    issuer_address  VARCHAR(66) NOT NULL,
    claim_topics    BIGINT[]    NOT NULL,
    legal_entity_id UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    added_tx        VARCHAR(66),
    removed_tx      VARCHAR(66),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at      TIMESTAMPTZ,
    -- 4-eyes, as for permission_grant above.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    added_block_number BIGINT,
    added_block_hash VARCHAR(128),
    removed_block_number BIGINT,
    removed_block_hash VARCHAR(128),
    CONSTRAINT chk_ecosystem_trusted_issuer_status CHECK (
        status IN ('PENDING','ACTIVE','REMOVAL_PENDING','REMOVED','REMOVAL_FAILED','FAILED')
    ),
    CONSTRAINT chk_trusted_issuer_removal_provenance CHECK (
        (status = 'REMOVED') =
        (removed_block_number IS NOT NULL
            AND removed_block_hash IS NOT NULL
            AND removed_tx IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_ecosystem_trusted_issuer_live
    ON ecosystem_trusted_issuer (chain_config_id, lower(issuer_address))
    WHERE status IN ('PENDING','ACTIVE');
CREATE INDEX idx_ecosystem_trusted_issuer_status
    ON ecosystem_trusted_issuer (status);

-- ═══════════════════════════════════════════════════════════════════════════
-- PAYMENT RAILS (operator-curated payment methods for the cash leg)
-- ═══════════════════════════════════════════════════════════════════════════

-- The operator curates the payment methods the registry provides as ready-made rails:
-- Stablecoin payment rails with operator-recorded MiCAR disclosure metadata, the Pontes instant-payment
-- API, ERC-7573-style DvP settlement, and classic SEPA. dApp manifests reference rails
-- by code (advisory model — dApps may also declare custom methods they implement
-- themselves; the operator sees both at review time).
CREATE TABLE payment_rail (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(60)   NOT NULL UNIQUE,
    display_name        VARCHAR(200)  NOT NULL,
    rail_type           VARCHAR(20)   NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    decimals            INT,
    description         VARCHAR(1000),
    -- MiCAR metadata (rail_type = STABLECOIN): who issues the e-money token and under
    -- which authorization (Title IV EMT) — surfaced to publishers and investors.
    issuer_name         VARCHAR(200),
    issuer_lei          VARCHAR(20),
    micar_authorization VARCHAR(200),
    emt_flag            BOOLEAN       NOT NULL DEFAULT false,
    -- Holder-facing MiCAR Title IV disclosure (Art. 49 redemption, Art. 51 white paper) —
    -- disclosure-surfacing only; Registerwerk is not the EMT issuer.
    white_paper_url     VARCHAR(500),
    redemption_at_par   BOOLEAN       NOT NULL DEFAULT false,
    -- The fields above are operator-entered free text. These three record that an operator
    -- attested to having checked them, so "disclosed" is distinguishable from "actually
    -- checked against a real register". This is an auditable attestation, NOT a live
    -- register cross-check performed by Registerwerk.
    micar_verified      BOOLEAN       NOT NULL DEFAULT FALSE,
    micar_verified_at   TIMESTAMPTZ,
    micar_verified_by   UUID,
    enabled             BOOLEAN       NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 5A-11: payment-rail separation of duties and content-bound MiCAR attestation.
    --
    -- created_by / updated_by let the attester be checked against whoever created or last
    -- changed the rail (an operator must not attest their own entries).
    -- micar_attested_fingerprint binds the attestation to the exact facts that were attested
    -- (SHA-256 over a canonical string incl. chain token addresses, see PaymentRailAttestation):
    -- an attestation whose fingerprint no longer matches is treated as void; micar_verified = true
    -- without a fingerprint is void too (fail closed).
    -- disabled_reason records why a rail was switched off automatically (attestation cleared on
    -- an EMT rail) so that operators and the marketplace can show a banner instead of a silent gap.
    created_by                  UUID,
    updated_by                  UUID,
    micar_attested_fingerprint  VARCHAR(64),
    disabled_reason             VARCHAR(100),
    CONSTRAINT chk_payment_rail_type CHECK (
        rail_type IN ('STABLECOIN','PONTES_API','ERC7573_DVP','OFFCHAIN_SEPA')
    )
);
CREATE INDEX idx_payment_rail_enabled ON payment_rail (enabled);

-- Onchain deployment of a rail per chain (stablecoin token contract, DvP settlement
-- contract); API/off-chain rails have no rows here.
CREATE TABLE payment_rail_chain_address (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_rail_id UUID        NOT NULL REFERENCES payment_rail(id) ON DELETE CASCADE,
    chain_config_id UUID        NOT NULL REFERENCES chain_config(id),
    token_address   VARCHAR(66) NOT NULL,
    CONSTRAINT uq_payment_rail_chain UNIQUE (payment_rail_id, chain_config_id)
);
CREATE INDEX idx_payment_rail_chain_rail ON payment_rail_chain_address (payment_rail_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- DAPP MARKETPLACE (metadata-only listings)
-- ═══════════════════════════════════════════════════════════════════════════

-- Publishers submit a signed manifest describing their tokenization dApp (contracts,
-- required permissions/claims, container images pinned by OCI digest). The operator
-- reviews with 4-eyes; approval anchors keccak256(manifest_raw) in the onchain
-- DappRegistry. We store no artifacts — integrity comes from digests + the onchain hash.
CREATE TABLE dapp_listing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publisher_entity_id UUID         NOT NULL REFERENCES legal_entity(id),
    chain_config_id     UUID         NOT NULL REFERENCES chain_config(id),
    slug                VARCHAR(60)  NOT NULL UNIQUE,
    dapp_id_hash        VARCHAR(66)  NOT NULL,
    name                VARCHAR(200) NOT NULL,
    category            VARCHAR(60)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    current_version_id  UUID,
    contact_email       VARCHAR(320),
    docs_url            VARCHAR(500),
    pricing_note        VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dapp_listing_status CHECK (
        status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','DEPRECATED','DELISTED')
    )
);
CREATE INDEX idx_dapp_listing_status ON dapp_listing (status);
CREATE INDEX idx_dapp_listing_publisher ON dapp_listing (publisher_entity_id);

CREATE TABLE dapp_version (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id         UUID         NOT NULL REFERENCES dapp_listing(id),
    version            VARCHAR(40)  NOT NULL,
    -- verbatim manifest bytes — the keccak256 hash input; jsonb would normalize and break hashing
    manifest_raw       TEXT,
    -- normalized copy for querying only
    manifest_json      JSONB,
    manifest_hash      VARCHAR(66),
    manifest_signature VARCHAR(200),
    signer_wallet      VARCHAR(66),
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    review_notes       TEXT,
    submitted_at       TIMESTAMPTZ,
    reviewed_by        UUID,
    reviewed_at        TIMESTAMPTZ,
    onchain_tx         VARCHAR(66),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    anchor_chain_config_id UUID REFERENCES chain_config(id),
    anchor_block_number BIGINT,
    anchor_block_hash VARCHAR(128),
    CONSTRAINT chk_dapp_version_status CHECK (
        status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','SUPERSEDED')
    ),
    CONSTRAINT uq_dapp_version UNIQUE (listing_id, version)
);
CREATE INDEX idx_dapp_version_listing ON dapp_version (listing_id, created_at DESC);
CREATE INDEX idx_dapp_version_review_queue ON dapp_version (submitted_at)
    WHERE status IN ('SUBMITTED','IN_REVIEW');

CREATE TABLE dapp_required_permission (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      UUID         NOT NULL REFERENCES dapp_version(id) ON DELETE CASCADE,
    permission_code VARCHAR(120) NOT NULL,
    permission_hash VARCHAR(66)  NOT NULL,
    claim_topics    BIGINT[]     NOT NULL DEFAULT '{}',
    rationale       VARCHAR(500)
);
CREATE INDEX idx_dapp_required_permission_version ON dapp_required_permission (version_id);

-- Payment methods a dApp version declares in its manifest: either a reference to an
-- operator-curated payment_rail (by code — soft reference so rails can be disabled
-- without FK pain; re-checked at approval) or a custom method the dApp implements.
CREATE TABLE dapp_payment_method (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id         UUID        NOT NULL REFERENCES dapp_version(id) ON DELETE CASCADE,
    method_type        VARCHAR(10) NOT NULL,
    rail_code          VARCHAR(60),
    custom_name        VARCHAR(120),
    custom_description VARCHAR(500),
    currency           VARCHAR(3),
    note               VARCHAR(500),
    CONSTRAINT chk_dapp_payment_method_type CHECK (method_type IN ('RAIL','CUSTOM')),
    CONSTRAINT chk_dapp_payment_method_shape CHECK ((method_type = 'RAIL') = (rail_code IS NOT NULL))
);
CREATE INDEX idx_dapp_payment_method_version ON dapp_payment_method (version_id);

CREATE TABLE dapp_review_event (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID        NOT NULL REFERENCES dapp_version(id),
    action     VARCHAR(30) NOT NULL,
    actor_id   UUID,
    notes      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dapp_review_event_version ON dapp_review_event (version_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- SEED DATA
-- ═══════════════════════════════════════════════════════════════════════════

-- Chain registry — EVM mainnet/testnet
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES
('ETHEREUM_MAINNET',  'Ethereum Mainnet',      'EVM', 'MAINNET', 1,
 'https://mainnet.infura.io/v3/changeme', 'wss://mainnet.infura.io/ws/v3/changeme',
 'https://etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-mainnet'),
('ETHEREUM_SEPOLIA',  'Ethereum Sepolia',       'EVM', 'TESTNET', 11155111,
 'https://sepolia.infura.io/v3/changeme', 'wss://sepolia.infura.io/ws/v3/changeme',
 'https://sepolia.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-sepolia'),
('POLYGON_MAINNET',   'Polygon Mainnet',        'EVM', 'MAINNET', 137,
 'https://polygon-mainnet.infura.io/v3/changeme', 'wss://polygon-mainnet.infura.io/ws/v3/changeme',
 'https://polygonscan.com', 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-mainnet'),
('POLYGON_AMOY',      'Polygon Amoy Testnet',   'EVM', 'TESTNET', 80002,
 'https://polygon-amoy.infura.io/v3/changeme', 'wss://polygon-amoy.infura.io/ws/v3/changeme',
 'https://amoy.polygonscan.com', 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-amoy'),
('BASE_MAINNET',      'Base Mainnet',           'EVM', 'MAINNET', 8453,
 'https://mainnet.base.org', 'wss://mainnet.base.org',
 'https://basescan.org', 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-mainnet'),
('BASE_SEPOLIA',      'Base Sepolia Testnet',   'EVM', 'TESTNET', 84532,
 'https://sepolia.base.org', 'wss://sepolia.base.org',
 'https://sepolia.basescan.org', 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-sepolia'),
('SOLANA_MAINNET',    'Solana Mainnet Beta',    'SOLANA', 'MAINNET', NULL,
 'https://api.mainnet-beta.solana.com', 'wss://api.mainnet-beta.solana.com',
 'https://solscan.io', NULL, NULL),
('SOLANA_DEVNET',     'Solana Devnet',          'SOLANA', 'TESTNET', NULL,
 'https://api.devnet.solana.com', 'wss://api.devnet.solana.com',
 'https://solscan.io', NULL, NULL),
('FHENIX_MAINNET',    'Fhenix Mainnet',         'EVM', 'MAINNET', 21888,
 'https://api.fhenix.zone:7747', 'wss://api.fhenix.zone:7748',
 'https://explorer.fhenix.zone', NULL, NULL),
('FHENIX_HELIUM',     'Fhenix Helium Testnet',  'EVM', 'TESTNET', 8008135,
 'https://api.helium.fhenix.zone:7747', 'wss://api.helium.fhenix.zone:7748',
 'https://explorer.helium.fhenix.zone', NULL, NULL),
('INCO_MAINNET',      'Inco Mainnet',           'EVM', 'MAINNET', 9090,
 'https://mainnet.inco.org', 'wss://mainnet.inco.org',
 'https://explorer.inco.org', NULL, NULL),
('INCO_RIVEST',       'Inco Rivest Testnet',    'EVM', 'TESTNET', 21097,
 'https://validator.rivest.inco.org', 'wss://validator.rivest.inco.org',
 'https://explorer.rivest.inco.org', NULL, NULL),
('ARBITRUM_MAINNET',  'Arbitrum One',           'EVM', 'MAINNET', 42161,
 'https://arbitrum.publicnode.com', 'wss://arbitrum-one.publicnode.com',
 'https://arbiscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-mainnet'),
('ARBITRUM_SEPOLIA',  'Arbitrum Sepolia',       'EVM', 'TESTNET', 421614,
 'https://sepolia-rollup.arbitrum.io/rpc', 'wss://sepolia-rollup.arbitrum.io/ws',
 'https://sepolia.arbiscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-sepolia'),
('AVALANCHE_MAINNET', 'Avalanche C-Chain',      'EVM', 'MAINNET', 43114,
 'https://api.avax.network/ext/bc/C/rpc', 'wss://api.avax.network/ext/bc/C/ws',
 'https://snowtrace.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-mainnet'),
('AVALANCHE_FUJI',    'Avalanche Fuji Testnet', 'EVM', 'TESTNET', 43113,
 'https://api.avax-test.network/ext/bc/C/rpc', 'wss://api.avax-test.network/ext/bc/C/ws',
 'https://testnet.snowtrace.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-fuji'),
('OPTIMISM_MAINNET',  'Optimism',               'EVM', 'MAINNET', 10,
 'https://mainnet.optimism.io', 'wss://ws-mainnet.optimism.io',
 'https://optimistic.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-mainnet'),
('OPTIMISM_SEPOLIA',  'Optimism Sepolia',       'EVM', 'TESTNET', 11155420,
 'https://sepolia.optimism.io', 'wss://sepolia.optimism.io',
 'https://sepolia-optimism.etherscan.io', 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-sepolia');

-- Non-EVM chains (enabled=false — flip when client integration is complete)
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url, enabled) VALUES
('STARKNET_MAINNET', 'Starknet Mainnet', 'STARKNET', 'MAINNET', NULL,
 'https://rpc.starknet.lava.build', NULL, 'https://starkscan.co', false),
('STARKNET_SEPOLIA', 'Starknet Sepolia', 'STARKNET', 'TESTNET', NULL,
 'https://api.cartridge.gg/x/starknet/sepolia', NULL, 'https://sepolia.starkscan.co', false),
('STELLAR_MAINNET',  'Stellar Mainnet',  'STELLAR',  'MAINNET', NULL,
 'https://horizon.stellar.org', NULL, 'https://stellar.expert/explorer/public', false),
('STELLAR_TESTNET',  'Stellar Testnet',  'STELLAR',  'TESTNET', NULL,
 'https://horizon-testnet.stellar.org', NULL, 'https://stellar.expert/explorer/testnet', false),
('CANTON_MAINNET',   'Canton Mainnet',   'CANTON',   'MAINNET', NULL,
 '', NULL, 'https://canton.network', false),
('CANTON_DEVNET',    'Canton DevNet',    'CANTON',   'TESTNET', NULL,
 '', NULL, 'https://canton.network', false);

-- Canton synchronizer config
UPDATE chain_config
   SET application_id = 'registerwerk', synchronizer_id = 'global-synchronizer'
 WHERE identifier = 'CANTON_MAINNET';

UPDATE chain_config
   SET application_id = 'registerwerk', synchronizer_id = 'dev-synchronizer'
 WHERE identifier = 'CANTON_DEVNET';

-- Primary RPC nodes (skip chains with empty URL)
INSERT INTO rpc_node (chain_config_id, url, label, enabled)
SELECT id, rpc_url, 'Primary', true
FROM chain_config
WHERE rpc_url IS NOT NULL AND rpc_url <> '';

-- DORA Art. 28 — pre-populated ICT third-party register
INSERT INTO third_party_provider (name, category, criticality) VALUES
    ('Ethereum RPC (Infura/Alchemy)', 'BLOCKCHAIN_RPC',     'CRITICAL'),
    ('The Graph (Graph Node)',        'GRAPH_NODE',          'IMPORTANT'),
    ('Solana RPC',                    'BLOCKCHAIN_RPC',      'IMPORTANT'),
    ('Canton Synchronizer',           'BLOCKCHAIN_RPC',      'IMPORTANT'),
    ('Starknet RPC',                  'BLOCKCHAIN_RPC',      'STANDARD'),
    ('Stellar Horizon',               'BLOCKCHAIN_RPC',      'STANDARD'),
    ('AWS S3 (document storage)',     'CLOUD_PROVIDER',      'IMPORTANT'),
    ('OpenSanctions (screening)',     'SANCTIONS_SCREENING', 'CRITICAL'),
    ('SMTP mail relay',               'EMAIL_SERVICE',       'STANDARD')
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- SHEDLOCK — multi-instance safety for @Scheduled jobs
-- ═══════════════════════════════════════════════════════════════════════════
-- Every @Scheduled job in this codebase (on-chain tx pollers, indexers, reporting
-- crons) would otherwise run on EVERY backend instance with no coordination, so scaling
-- out to more than one instance for hot-failover would double-submit on-chain
-- transactions and double-process events. ShedLock serializes each job across
-- threads AND instances via this table (standard ShedLock JDBC schema).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- LOGIN ATTEMPT TRACKING — multi-instance-safe brute-force throttle
-- ═══════════════════════════════════════════════════════════════════════════
-- A per-instance in-memory counter would silently multiply the effective lockout
-- threshold by the instance count behind a load balancer. This table makes the
-- counter shared and consistent across every instance.
CREATE TABLE login_attempt (
    login_key      VARCHAR(320) NOT NULL PRIMARY KEY,
    attempt_count  INT          NOT NULL DEFAULT 1,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Keys are typed and prefixed ("p|email|ip", "i|ip", "a|email", "g|"); counters use an explicit window
    -- and a pair lock grows exponentially per episode.
    kind          VARCHAR(8)  NOT NULL DEFAULT 'LEGACY',
    window_start  TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until  TIMESTAMPTZ,
    lock_episodes INT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_login_attempt_updated_at ON login_attempt (updated_at);
COMMENT ON TABLE login_attempt IS
    'Shared login throttle state (LoginAttemptLimiter): PAIR (email+source IP, hard lock with '
    'exponential backoff), IP (sliding failure window), ACCOUNT (progressive delay only, never a '
    'lock) and GLOBAL. Bounded: purged by LoginAttemptPurgeJob and capped by a row-count guard.';

-- ═══════════════════════════════════════════════════════════════════════════
-- GAS SPONSORSHIP POLICY — ERC-4337 EwpgPaymaster budgets
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE gas_sponsorship_policy (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_deployment_id  UUID REFERENCES asset_deployment(id),
    issuer_id            UUID,
    sponsor              VARCHAR(20) NOT NULL,
    monthly_cap_eth      NUMERIC(38,18),
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID,
    CONSTRAINT chk_gas_sponsorship_sponsor CHECK (
        sponsor IN ('OPERATOR','ISSUER')
    ),
    CONSTRAINT chk_gas_sponsorship_scope CHECK (
        (asset_deployment_id IS NOT NULL AND issuer_id IS NULL)
        OR (asset_deployment_id IS NULL AND issuer_id IS NOT NULL)
    )
);
CREATE INDEX idx_gas_sponsorship_deployment ON gas_sponsorship_policy (asset_deployment_id);
CREATE INDEX idx_gas_sponsorship_issuer     ON gas_sponsorship_policy (issuer_id) WHERE asset_deployment_id IS NULL;

-- EwpgPaymaster vouchers (review phase 2, T2-01/T2-02): every sponsored UserOperation needs a
-- voucher signed by the backend. Each issued voucher is recorded with its worst-case prefund so
-- the policy's monthly_cap_eth is enforced (it previously had no effect anywhere).
CREATE TABLE gas_sponsorship_voucher (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id            UUID NOT NULL REFERENCES gas_sponsorship_policy(id),
    asset_deployment_id  UUID NOT NULL REFERENCES asset_deployment(id),
    entity_id            UUID NOT NULL,
    sender               VARCHAR(42) NOT NULL,
    chain_id             BIGINT NOT NULL,
    user_op_nonce        NUMERIC(78,0) NOT NULL,
    max_cost_wei         NUMERIC(78,0) NOT NULL CHECK (max_cost_wei >= 0),
    valid_until          TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           UUID
);
CREATE INDEX idx_gas_sponsorship_voucher_policy_created ON gas_sponsorship_voucher (policy_id, created_at);
CREATE UNIQUE INDEX uq_gas_sponsorship_voucher_policy_sender_nonce
    ON gas_sponsorship_voucher (policy_id, sender, user_op_nonce);
CREATE INDEX idx_gas_sponsorship_voucher_policy_entity_created
    ON gas_sponsorship_voucher (policy_id, entity_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- DORA Art. 24/25 — digital operational resilience testing
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE resilience_test (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_type               VARCHAR(30) NOT NULL,
    scope                   TEXT NOT NULL,
    tlpt_required           BOOLEAN NOT NULL DEFAULT FALSE,
    third_party_provider_id UUID REFERENCES third_party_provider(id),
    performed_at            DATE NOT NULL,
    next_due_date           DATE,
    result                  VARCHAR(20) NOT NULL,
    findings                TEXT,
    tester_name             TEXT,
    report_ref              TEXT,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resilience_test_due ON resilience_test (next_due_date) WHERE next_due_date IS NOT NULL;
CREATE INDEX idx_resilience_test_provider ON resilience_test (third_party_provider_id) WHERE third_party_provider_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- LENDING MODULE — repo/collateralized-lending read-model
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Backs the `lending` module: a thin cache in front of the on-chain EwpgRepoMarket /
-- EwpgRepoVault view functions (see contracts/src/lending/), NOT a ledger. The contracts
-- remain the sole source of truth for balances/debt; these tables exist so the customer
-- frontend has a fast, queryable "my positions" / "markets" view without every page load
-- fanning out to eth_call for every known wallet across every market.
CREATE TABLE lending_market (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id          UUID         NOT NULL REFERENCES chain_config(id),
    market_address           VARCHAR(66)  NOT NULL,
    vault_address            VARCHAR(66),
    collateral_asset_id      UUID REFERENCES asset(id),
    collateral_token_address VARCHAR(66)  NOT NULL,
    loan_token_address       VARCHAR(66)  NOT NULL,
    loan_rail_code           VARCHAR(60)  REFERENCES payment_rail(code),
    lltv_bps                 INTEGER      NOT NULL,
    liquidation_bonus_bps    INTEGER      NOT NULL,
    base_rate_wad            NUMERIC(38,0) NOT NULL,
    slope_wad                NUMERIC(38,0) NOT NULL,
    price_oracle_address     VARCHAR(66)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    registered_by            UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Immutable EwpgRepoMarket parameters verified from chain at registration time. NULL
    -- fails closed for new borrowing until an operator re-registers or
    -- reconciles them against the deployed contract; inventing a max-LTV would be
    -- materially unsafe.
    max_ltv_bps INTEGER,
    max_price_age_seconds NUMERIC(78,0),
    liquidation_grace_period_seconds NUMERIC(78,0),
    loan_token_decimals INTEGER,
    -- 5B-09: a market is only offered to customers while its on-chain binding (factory provenance,
    -- collateral token == the asset's confirmed deployment, loan token == the enabled rail's token)
    -- verifies. FALSE (the default) fails closed: a market without a recorded successful verification is
    -- not offered until the admin re-verification succeeds.
    binding_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    binding_verified_at  TIMESTAMPTZ,
    binding_failure      VARCHAR(500),
    code_hash            VARCHAR(66),
    -- 5B-11: true when surplusOf(address) exists on the deployed market; a failed read on such a
    -- market is an error, not a zero. Defaults to true (fail closed) until probed.
    surplus_supported    BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 5B-10: collateral token balance of the market is below its recorded totalCollateral.
    collateral_shortfall BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_lending_market_address UNIQUE (chain_config_id, market_address),
    CONSTRAINT chk_lending_market_status CHECK (status IN ('ACTIVE','PAUSED','RETIRED')),
    CONSTRAINT chk_lending_market_lltv CHECK (lltv_bps > 0 AND lltv_bps <= 10000),
    CONSTRAINT chk_lending_market_max_ltv
        CHECK (max_ltv_bps IS NULL OR (max_ltv_bps > 0 AND max_ltv_bps < lltv_bps)),
    CONSTRAINT chk_lending_market_loan_decimals
        CHECK (loan_token_decimals IS NULL OR (loan_token_decimals >= 0 AND loan_token_decimals <= 36))
);
CREATE INDEX idx_lending_market_chain ON lending_market (chain_config_id);
CREATE INDEX idx_lending_market_collateral_asset ON lending_market (collateral_asset_id);

-- Borrower positions — one row per (market, wallet), refreshed on demand by
-- LendingPositionService via a live debtOf/healthFactor/positions() read.
CREATE TABLE lending_position (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id         UUID         NOT NULL REFERENCES lending_market(id),
    wallet_address    VARCHAR(66)  NOT NULL,
    collateral_amount NUMERIC(78,0) NOT NULL DEFAULT 0,
    current_debt      NUMERIC(78,0) NOT NULL DEFAULT 0,
    health_factor_wad NUMERIC(78,0),
    -- Whether health_factor_wad can be trusted. NULL = not read (no debt, or the read itself
    -- failed); FALSE = read succeeded but the price backing it is unpriced or stale. Without
    -- this flag a stale mark is indistinguishable from a good one.
    health_factor_reliable BOOLEAN,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    last_synced_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- EwpgRepoMarket credits a liquidated borrower with loan-token cash when a liquidator's payment
    -- for whole collateral units exceeds the debt it closes (surplusOf / claimLiquidationSurplus).
    -- The position read-model caches it so the customer "My Loans" view can offer the claim.
    liquidation_surplus NUMERIC(78,0) NOT NULL DEFAULT 0,
    -- 5B-11: a failed on-chain read keeps the previous values and marks the row stale.
    sync_stale      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_sync_error VARCHAR(500),
    CONSTRAINT uq_lending_position UNIQUE (market_id, wallet_address),
    CONSTRAINT chk_lending_position_status CHECK (status IN ('OPEN','CLOSED','LIQUIDATED'))
);
CREATE INDEX idx_lending_position_wallet ON lending_position (wallet_address);
CREATE INDEX idx_lending_position_market ON lending_position (market_id);

CREATE TABLE lending_reconciliation_task (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id           UUID         NOT NULL REFERENCES lending_market(id),
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    source              VARCHAR(30)  NOT NULL,
    shortfall           NUMERIC(78,0) NOT NULL DEFAULT 0,
    token_admin_method  VARCHAR(60),
    detail              VARCHAR(1000),
    detected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         UUID,
    reconcile_tx_hash   VARCHAR(66),
    borrower_wallet     VARCHAR(66),
    attributed_amount   NUMERIC(78,0),
    forced_transfer_ref VARCHAR(66),
    legal_basis         VARCHAR(500),
    CONSTRAINT chk_lending_recon_status CHECK (status IN ('OPEN','SUBMITTED','RESOLVED')),
    CONSTRAINT chk_lending_recon_source CHECK (source IN ('BALANCE_GUARD','FORCED_TRANSFER_EVENT'))
);

-- at most one unresolved task per market: detections upsert into it
CREATE UNIQUE INDEX uq_lending_recon_open_market
    ON lending_reconciliation_task (market_id) WHERE status IN ('OPEN','SUBMITTED');
CREATE INDEX idx_lending_recon_status ON lending_reconciliation_task (status, detected_at);

-- Lender (supply-side) positions — one row per (market, wallet).
CREATE TABLE lending_supply_position (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    market_id      UUID         NOT NULL REFERENCES lending_market(id),
    wallet_address VARCHAR(66)  NOT NULL,
    current_claim  NUMERIC(78,0) NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lending_supply_position UNIQUE (market_id, wallet_address)
);
CREATE INDEX idx_lending_supply_position_wallet ON lending_supply_position (wallet_address);
CREATE INDEX idx_lending_supply_position_market ON lending_supply_position (market_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- ASSET_TOKEN_ADMIN — delegatable forcedTransfer/forcedApprove/forceBurn grants
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Structural analogue of holder_block above — same lifecycle shape (create/revoke, legal
-- basis, 4-eyes, auto-expiry).
CREATE TABLE asset_token_admin_grant (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id                UUID NOT NULL,
    asset_id                 UUID,
    wallet_address           TEXT NOT NULL,
    capability               VARCHAR(40) NOT NULL DEFAULT 'ASSET_TOKEN_ADMIN',
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    eligibility_basis        VARCHAR(40) NOT NULL,
    chain_config_id          UUID,
    legal_basis              TEXT NOT NULL,
    created_by               UUID NOT NULL,
    -- Set at GRANT time and never overwritten.
    dual_control_approver_id UUID,
    dual_control_approved_at TIMESTAMPTZ,
    expires_at               TIMESTAMPTZ,
    revoked_at               TIMESTAMPTZ,
    revoked_by               UUID,
    revoke_reason            TEXT,
    -- Revocation needs 4-eyes too, and needs its *own* pair: reusing the grant-time columns
    -- would erase the record of who approved the original grant.
    revoke_dual_control_approver_id UUID,
    revoke_dual_control_approved_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_asset_token_admin_grant_asset        ON asset_token_admin_grant (asset_id)  WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_entity        ON asset_token_admin_grant (entity_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_entity_asset   ON asset_token_admin_grant (entity_id, asset_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_asset_token_admin_grant_expires        ON asset_token_admin_grant (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- CUSTOMER SUPPORT — support tickets + threaded messages
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE support_ticket (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id         UUID NOT NULL REFERENCES legal_entity(id),
    created_by        UUID NOT NULL REFERENCES app_user(id),
    subject           VARCHAR(200) NOT NULL,
    description       TEXT NOT NULL,
    category          VARCHAR(30) NOT NULL,
    priority          VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_to       UUID REFERENCES app_user(id),
    resolution_notes  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    CONSTRAINT chk_support_ticket_category CHECK (
        category IN ('TECHNICAL','COMPLIANCE','BILLING','ASSET_ISSUE','TRADING','ONBOARDING','OTHER')
    ),
    CONSTRAINT chk_support_ticket_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT chk_support_ticket_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED'))
);
CREATE INDEX idx_support_ticket_entity ON support_ticket (entity_id);
CREATE INDEX idx_support_ticket_status ON support_ticket (status);
CREATE INDEX idx_support_ticket_assigned ON support_ticket (assigned_to) WHERE assigned_to IS NOT NULL;

CREATE TABLE support_ticket_message (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id        UUID NOT NULL REFERENCES support_ticket(id),
    author_id        UUID NOT NULL REFERENCES app_user(id),
    author_is_operator BOOLEAN NOT NULL,
    body             TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_ticket_message_ticket ON support_ticket_message (ticket_id, created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- PORTFOLIO MIGRATION — investor off-ramp to a successor/competitor registrar
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Investor-side counterpart to register_transfer above (§§21/22 eWpG, asset-scoped).
CREATE TABLE portfolio_migration_request (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_entity_id       UUID NOT NULL REFERENCES legal_entity(id),
    asset_id                 UUID NOT NULL REFERENCES asset(id),
    holder_id                UUID NOT NULL,
    destination_registrar_name TEXT,
    destination_registrar_identifier TEXT,
    destination_wallet_address TEXT,
    reason                   TEXT NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    -- INITIATED, EXPORTED, HANDED_OVER, COMPLETED, CANCELLED (mirrors register_transfer.status).
    export_hash              VARCHAR(66),
    export_manifest          JSONB,
    onchain_tx_hash          VARCHAR(66),
    initiated_by             UUID,
    initiated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at              TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- T3-17: the portfolio-migration handover is verified against indexed chain data. Where no
    -- indexed deployment exists (off-chain register, Solana/Canton) the operator records
    -- an attestation instead (the endpoint is already step-up + 4-eyes).
    operator_attestation TEXT,
    -- T3-07 (C-05b): consent reference of the beneficiary of third-party rights / disposal
    -- restrictions on the migrated entry; required when those §17(2) attributes are set.
    beneficiary_consent_ref TEXT
);
CREATE INDEX idx_portfolio_migration_investor ON portfolio_migration_request (investor_entity_id);
CREATE INDEX idx_portfolio_migration_holder ON portfolio_migration_request (holder_id);
CREATE INDEX idx_portfolio_migration_status ON portfolio_migration_request (status)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');

-- ═══════════════════════════════════════════════════════════════════════════
-- DEFAULT RPC AVAILABILITY
-- ═══════════════════════════════════════════════════════════════════════════
-- Placeholder endpoints remain visible but disabled until an operator configures them.
UPDATE rpc_node
SET    enabled = false
WHERE  url LIKE '%/v3/changeme';

UPDATE rpc_node
SET    enabled = false
WHERE  url IN (
    'https://api.fhenix.zone:7747',
    'https://api.helium.fhenix.zone:7747',
    'https://mainnet.inco.org',
    'https://validator.rivest.inco.org'
);

-- Graph Node indexing is opt-in per chain.
UPDATE chain_config
SET    graph_node_url      = NULL,
       graph_subgraph_name = NULL
WHERE  graph_node_url = 'http://graph-node:8000/subgraphs/name';

-- ═══════════════════════════════════════════════════════════════════════════
-- DATABASE-BACKED WALLET KEYSTORE
-- ═══════════════════════════════════════════════════════════════════════════
-- Stores KEK-wrapped keys and ciphertext; it never contains plaintext key material.
CREATE TABLE wallet_keystore_blob (
    relative_path VARCHAR(255) PRIMARY KEY,
    content       BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ═══════════════════════════════════════════════════════════════════════════
-- PRIMARY-MARKET SUBSCRIPTION ORDERS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE subscription_order (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            UUID NOT NULL REFERENCES asset(id),
    investor_entity_id  UUID NOT NULL,
    wallet_address      TEXT NOT NULL,
    requested_amount    NUMERIC(38,18) NOT NULL,
    allocated_amount    NUMERIC(38,18),
    status              VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_at        TIMESTAMPTZ,
    allocated_by        UUID,
    confirmed_at        TIMESTAMPTZ,
    resulting_holder_id UUID,
    rejection_reason    TEXT,
    version                BIGINT NOT NULL DEFAULT 0,
    accepted_at            TIMESTAMPTZ,
    allocation_expires_at  TIMESTAMPTZ,
    amount_due             NUMERIC(38,18),
    payment_currency       VARCHAR(10),
    paid_amount            NUMERIC(38,18),
    refund_due             NUMERIC(38,18),
    payment_reference      TEXT,
    payment_value_date     DATE,
    payment_confirmed_at   TIMESTAMPTZ,
    payment_confirmed_by   UUID,
    settled_at             TIMESTAMPTZ,
    settlement_tx_id       UUID,
    lapsed_at              TIMESTAMPTZ,
    release_reason         TEXT,
    -- Wave 0b C7: business state follows the chain OUTCOME, not the transaction submission.
    --
    -- 1. subscription_order: a mint is only submitted at settlement -> SETTLEMENT_PENDING; it becomes SETTLED once the
    --    mint transaction is final and the indexed MINT transfer is FINALIZED. A reverted / replaced mint ->
    --    SETTLEMENT_FAILED (retryable by settling again; a timed-out mint is NOT failed - it may still mine). The status
    --    column is VARCHAR(20) without a CHECK, so only the failure evidence is new.
    settlement_failed_at       TIMESTAMPTZ,
    settlement_failure_reason  TEXT
);
CREATE INDEX idx_subscription_order_asset ON subscription_order (asset_id);
CREATE INDEX idx_subscription_order_investor ON subscription_order (investor_entity_id);
CREATE INDEX idx_subscription_order_status ON subscription_order (asset_id, status);

-- Allocation-deadline sweep (ALLOCATED orders only). CONFIRMED is a legacy, read-only status.
CREATE INDEX idx_subscription_order_expiry ON subscription_order (allocation_expires_at)
    WHERE status = 'ALLOCATED';

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

CREATE TABLE asset_target_market_category (
    asset_id         UUID NOT NULL REFERENCES asset(id),
    client_category  VARCHAR(30) NOT NULL,
    PRIMARY KEY (asset_id, client_category),
    CONSTRAINT chk_asset_target_market_category CHECK (
        client_category IN ('RETAIL', 'PROFESSIONAL', 'ELIGIBLE_COUNTERPARTY')
    )
);

CREATE TABLE investor_limit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                  UUID NOT NULL REFERENCES asset(id),
    investor_entity_id        UUID NOT NULL,
    min_investment_override   NUMERIC(38, 8),
    max_holding_override      NUMERIC(38, 8),
    lockup_until              DATE,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                UUID,
    CONSTRAINT uq_investor_limit_asset_investor UNIQUE (asset_id, investor_entity_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- OUTBOUND WEBHOOKS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE webhook_subscription (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID NOT NULL,
    url          TEXT NOT NULL,
    secret       TEXT NOT NULL,
    event_types  TEXT NOT NULL,
    -- comma-separated WebhookEventType names; empty = all curated types
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    -- Phase 5 / K4: webhook hardening (5D-08, 5D-09).
    --  * secret at rest: `secret` now holds `enc:v1:<b64>` (AES-256-GCM under a KEK-wrapped DEK);
    --    WebhookStartupMaintenance is the one-off path off plaintext secrets; plaintext is refused.
    --  * rotation overlap, circuit breaker, URL-policy disable reason.
    --  * delivery: stable event id, coarse outcome (the raw HTTP code is no longer exposed), and a
    --    next_attempt_at schedule so the retry sweep can claim due rows with SKIP LOCKED.
    secret_previous_enc     TEXT,
    secret_rotated_at       TIMESTAMPTZ,
    key_version             INTEGER NOT NULL DEFAULT 1,
    disabled_reason         VARCHAR(40),
    consecutive_failures    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_webhook_subscription_entity ON webhook_subscription (entity_id);
CREATE INDEX idx_webhook_subscription_enabled ON webhook_subscription (entity_id, enabled);

CREATE TABLE webhook_delivery (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscription(id),
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_code   INTEGER,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_id         UUID DEFAULT gen_random_uuid() NOT NULL,
    outcome          VARCHAR(20),
    next_attempt_at  TIMESTAMPTZ,
    CONSTRAINT chk_webhook_delivery_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_webhook_delivery_outcome
            CHECK (outcome IS NULL OR outcome IN ('OK', 'RECEIVER_ERROR', 'UNREACHABLE', 'BLOCKED'))
);
CREATE INDEX idx_webhook_delivery_subscription ON webhook_delivery (subscription_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- IDEMPOTENCY KEYS
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE idempotency_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id        UUID NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    -- Phase 4 K7 (P4B-7): mandatory idempotency for money-moving admin/issuer APIs.
    --
    -- 1. idempotency_record was keyed by (entity_id, key) and the filter skipped every principal without an
    --    entity_id claim, i.e. every operator/REGISTRY_ADMIN token. The row now carries a scope: 'ENTITY' (the
    --    tenant, entity_id = legal entity) or 'USER' (entity_id = the JWT subject / user id, for operator tokens).
    scope VARCHAR(10) NOT NULL DEFAULT 'ENTITY',
    CONSTRAINT chk_idempotency_record_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_idempotency_record_scope CHECK (scope IN ('ENTITY', 'USER')),
    CONSTRAINT uq_idempotency_record_scope_key UNIQUE (scope, entity_id, idempotency_key)
);

-- Input for the cleanup job — old records (of either status; a crashed IN_PROGRESS row must not
-- block that key forever) are purged after the retention window.
CREATE INDEX idx_idempotency_record_created_at ON idempotency_record (created_at);
COMMENT ON COLUMN idempotency_record.entity_id IS
    'Scope id: the legal entity for scope ENTITY, the acting user (JWT sub) for scope USER.';

-- ═══════════════════════════════════════════════════════════════════════════
-- ACCESS RECERTIFICATION
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE access_review_campaign (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    due_date    DATE,
    started_by  UUID         NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_by   UUID,
    closed_at   TIMESTAMPTZ,
    CONSTRAINT chk_access_review_campaign_status CHECK (status IN ('OPEN','CLOSED'))
);

-- One row per app_user, snapshotted at campaign start — the roles snapshot is what's actually
-- being attested to, independent of any role change the account undergoes mid-campaign.
CREATE TABLE access_review_item (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID         NOT NULL REFERENCES access_review_campaign(id) ON DELETE CASCADE,
    app_user_id         UUID         NOT NULL REFERENCES app_user(id),
    email_snapshot      VARCHAR(320) NOT NULL,
    full_name_snapshot  VARCHAR(200),
    roles_snapshot      VARCHAR(500) NOT NULL,
    decision            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by         UUID,
    reviewed_at         TIMESTAMPTZ,
    notes               TEXT,
    enabled_snapshot BOOLEAN NOT NULL DEFAULT TRUE,
    proposed_by UUID,
    proposed_at TIMESTAMPTZ,
    sod_conflicts VARCHAR(300),
    reopened_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ux_access_review_item UNIQUE (campaign_id, app_user_id),
    CONSTRAINT chk_access_review_item_decision
        CHECK (decision IN ('PENDING','CONFIRMED','REVOKED','REVOKE_PROPOSED','STALE'))
);
CREATE INDEX idx_access_review_item_campaign ON access_review_item (campaign_id);
CREATE INDEX idx_access_review_item_app_user ON access_review_item (app_user_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- REPO RFQS AND QUOTES
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE repo_rfq (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    requester_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    requester_user_id UUID,
    side VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    collateral_quantity NUMERIC(96,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency VARCHAR(3) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    proposed_repo_rate NUMERIC(12,8),
    proposed_haircut_bps INTEGER,
    settlement_method VARCHAR(20) NOT NULL DEFAULT 'DVP',
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_repo_rfq_side CHECK (side IN ('BORROW_CASH', 'LEND_CASH')),
    CONSTRAINT ck_repo_rfq_visibility CHECK (visibility IN ('TARGETED', 'BROADCAST')),
    CONSTRAINT ck_repo_rfq_status CHECK (status IN ('OPEN', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_repo_rfq_settlement CHECK (settlement_method IN ('DVP', 'FOP')),
    CONSTRAINT ck_repo_rfq_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_rfq_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_rfq_dates CHECK (end_date > start_date),
    CONSTRAINT ck_repo_rfq_haircut CHECK (proposed_haircut_bps IS NULL OR proposed_haircut_bps BETWEEN 0 AND 10000)
);
CREATE INDEX idx_repo_rfq_requester ON repo_rfq(requester_entity_id, created_at DESC);
CREATE INDEX idx_repo_rfq_open ON repo_rfq(status, expires_at);

CREATE TABLE repo_rfq_target (
    repo_rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    target_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    PRIMARY KEY (repo_rfq_id, target_entity_id)
);
CREATE INDEX idx_repo_rfq_target_entity ON repo_rfq_target(target_entity_id, repo_rfq_id);

CREATE TABLE repo_quote (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL REFERENCES repo_rfq(id) ON DELETE CASCADE,
    quoting_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    quoting_user_id UUID,
    cash_amount NUMERIC(38,18) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ── quotes ──────────────────────────────────────────────────────────────────────────────────────
    quote_version INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT ck_repo_quote_cash CHECK (cash_amount > 0),
    CONSTRAINT ck_repo_quote_rate CHECK (repo_rate >= 0),
    CONSTRAINT ck_repo_quote_haircut CHECK (haircut_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_repo_quote_status
        CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED'))
);
CREATE INDEX idx_repo_quote_rfq ON repo_quote(rfq_id, created_at DESC);
CREATE INDEX idx_repo_quote_entity ON repo_quote(quoting_entity_id, created_at DESC);
CREATE UNIQUE INDEX uq_repo_quote_active_per_counterparty
    ON repo_quote(rfq_id, quoting_entity_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_repo_quote_version
    ON repo_quote(rfq_id, quoting_entity_id, quote_version);

-- ═══════════════════════════════════════════════════════════════════════════
-- REPO TRADE LIFECYCLE
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE repo_trade (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    rfq_id UUID NOT NULL UNIQUE REFERENCES repo_rfq(id),
    accepted_quote_id UUID NOT NULL UNIQUE REFERENCES repo_quote(id),
    cash_borrower_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    cash_lender_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    collateral_asset_id UUID NOT NULL REFERENCES asset(id),
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    collateral_quantity NUMERIC(96,18) NOT NULL,
    cash_amount NUMERIC(38,18) NOT NULL,
    cash_currency VARCHAR(3) NOT NULL,
    repo_rate NUMERIC(12,8) NOT NULL,
    haircut_bps INTEGER NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    repurchase_amount NUMERIC(38,18) NOT NULL,
    settlement_method VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    open_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    open_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_cash_confirmed BOOLEAN NOT NULL DEFAULT false,
    close_collateral_confirmed BOOLEAN NOT NULL DEFAULT false,
    margin_call_amount NUMERIC(38,18),
    margin_call_due_at TIMESTAMPTZ,
    pending_substitution_asset_id UUID REFERENCES asset(id),
    pending_substitution_quantity NUMERIC(96,18),
    substitution_requested_by UUID REFERENCES legal_entity(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ── trades ──────────────────────────────────────────────────────────────────────────────────────
    terms_hash VARCHAR(64),
    accepted_quote_version INTEGER,
    day_count_basis INTEGER NOT NULL DEFAULT 360,
    uti VARCHAR(52),
    venue VARCHAR(40) NOT NULL DEFAULT 'BILATERAL_UNREGULATED',
    collateral_reuse_consent BOOLEAN NOT NULL DEFAULT false,
    -- payer-side declarations (the payer says "sent"; the receiver confirms or disputes)
    open_cash_declared_at TIMESTAMPTZ,
    open_collateral_declared_at TIMESTAMPTZ,
    close_cash_declared_at TIMESTAMPTZ,
    close_collateral_declared_at TIMESTAMPTZ,
    -- margin call snapshot
    margin_valuation_reference VARCHAR(200),
    margin_valuation_amount NUMERIC(38,18),
    margin_haircut_bps INTEGER,
    margin_delivered_at TIMESTAMPTZ,
    margin_delivered_reference VARCHAR(200),
    -- default: notice, grace, direction
    default_notice_at TIMESTAMPTZ,
    default_notice_by UUID REFERENCES legal_entity(id),
    default_notice_ground VARCHAR(30),
    defaulting_party_entity_id UUID REFERENCES legal_entity(id),
    default_ground VARCHAR(30),
    -- dispute
    dispute_reason VARCHAR(1000),
    pre_dispute_status VARCHAR(30),
    disputed_at TIMESTAMPTZ,
    disputed_by UUID REFERENCES legal_entity(id),
    CONSTRAINT ck_repo_trade_quantity CHECK (collateral_quantity > 0),
    CONSTRAINT ck_repo_trade_cash CHECK (cash_amount > 0 AND repurchase_amount >= cash_amount),
    CONSTRAINT ck_repo_trade_parties CHECK (cash_borrower_entity_id <> cash_lender_entity_id),
    CONSTRAINT ck_repo_trade_haircut CHECK (haircut_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_repo_trade_status CHECK (status IN (
        'PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'DISPUTED', 'CLOSED', 'DEFAULTED', 'CANCELLED')),
    CONSTRAINT ck_repo_trade_day_count CHECK (day_count_basis IN (360, 365)),
    CONSTRAINT ck_repo_trade_pre_dispute CHECK (
        pre_dispute_status IS NULL OR pre_dispute_status IN
            ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE'))
);
CREATE INDEX idx_repo_trade_party_borrower ON repo_trade(cash_borrower_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_party_lender ON repo_trade(cash_lender_entity_id, created_at DESC);
CREATE INDEX idx_repo_trade_status ON repo_trade(status, end_date);
CREATE UNIQUE INDEX uq_repo_trade_uti ON repo_trade(uti) WHERE uti IS NOT NULL;

-- encumbrance / redemption-blocker lookups: open trades per collateral asset and per borrower
CREATE INDEX idx_repo_trade_open_collateral ON repo_trade(collateral_asset_id, cash_borrower_entity_id)
    WHERE status IN ('PENDING_OPEN_SETTLEMENT', 'OPEN', 'MARGIN_CALL', 'PENDING_CLOSE', 'DISPUTED');

CREATE TABLE repo_lifecycle_event (
    id UUID PRIMARY KEY,
    repo_trade_id UUID NOT NULL REFERENCES repo_trade(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    actor_entity_id UUID REFERENCES legal_entity(id),
    actor_user_id UUID,
    amount NUMERIC(38,18),
    asset_id UUID REFERENCES asset(id),
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    quantity NUMERIC(96,18),
    reference VARCHAR(200),
    note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_repo_event_trade ON repo_lifecycle_event(repo_trade_id, created_at);

-- ── substitution requests ───────────────────────────────────────────────────────────────────────
-- PENDING -> APPROVED (replacement/return legs outstanding) -> COMPLETED, or REJECTED / WITHDRAWN / EXPIRED.
CREATE TABLE repo_substitution_request (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    repo_trade_id UUID NOT NULL REFERENCES repo_trade(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES asset(id),
    -- Token quantities are raw base units, like asset_holder.nominal_amount: NUMERIC(96,18) holds a full uint256 at scale 18.
    quantity NUMERIC(96,18) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_by UUID NOT NULL REFERENCES legal_entity(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by UUID REFERENCES legal_entity(id),
    decided_at TIMESTAMPTZ,
    replacement_received_at TIMESTAMPTZ,
    original_returned_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    note VARCHAR(1000),
    CONSTRAINT ck_repo_substitution_status
        CHECK (status IN ('PENDING', 'APPROVED', 'COMPLETED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_repo_substitution_quantity CHECK (quantity > 0)
);
CREATE INDEX idx_repo_substitution_trade ON repo_substitution_request(repo_trade_id, requested_at);

-- at most one live (pending or approved-but-unsettled) substitution per trade
CREATE UNIQUE INDEX uq_repo_substitution_live ON repo_substitution_request(repo_trade_id)
    WHERE status IN ('PENDING', 'APPROVED');

-- ── participant directory (opt-in, T5-07) ───────────────────────────────────────────────────────
-- Companies are NOT opted in by default: the desk is release-gated and participation is a
-- deliberate act of a company administrator.
CREATE TABLE repo_desk_participant (
    entity_id UUID PRIMARY KEY REFERENCES legal_entity(id),
    opted_in_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    opted_in_by UUID,
    listed BOOLEAN NOT NULL DEFAULT false,
    opted_out_at TIMESTAMPTZ
);

-- ═══════════════════════════════════════════════════════════════════════════
-- BLOCK FINALITY LEDGER
-- ═══════════════════════════════════════════════════════════════════════════
-- The finality module's own ledger of blocks observed while still "unsettled" (PROVISIONAL or
-- SAFE) — see BlockFinalityFeed's javadoc for exactly what is and isn't tracked here. Owned by
-- the finality module, fed by the indexer's ReorgGuard, so "what level did block N on chain C
-- reach, and was it ever retracted" is answerable without importing the indexer module or
-- scanning token_transfer.
CREATE TABLE block_finality (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id   UUID         NOT NULL REFERENCES chain_config(id),
    block_number      BIGINT       NOT NULL,
    block_hash        VARCHAR(128),
    finality_level    VARCHAR(16)  NOT NULL,
    observed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    canonical BOOLEAN NOT NULL DEFAULT TRUE,
    orphaned_at TIMESTAMPTZ,
    CONSTRAINT chk_block_finality_level CHECK (finality_level IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED')),
    -- The producer contract still permits a null/non-hash chain identity for legacy and non-EVM
    -- probes. NULLS NOT DISTINCT prevents duplicate unidentified incarnations at one height until
    -- the chain-specific phase upgrades every producer to a true protocol block hash.
    CONSTRAINT uq_block_finality_incarnation
        UNIQUE NULLS NOT DISTINCT (chain_config_id, block_number, block_hash),
    CONSTRAINT ck_block_finality_canonical_level CHECK (
        (canonical AND finality_level <> 'ORPHANED')
        OR (NOT canonical AND finality_level = 'ORPHANED')
    ),
    CONSTRAINT ck_block_finality_normalized_hex_hash CHECK (
        block_hash IS NULL
        OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR block_hash = lower(block_hash)
    )
);

-- The hot query: bulk-marking every row at/after a fork block ORPHANED, and looking up one
-- specific (chain, block).
CREATE INDEX idx_block_finality_chain_block ON block_finality (chain_config_id, block_number);

-- PostgreSQL partial uniqueness is the authoritative concurrency guard: history is unlimited,
-- but at most one incarnation can be current for a (chain, height).
CREATE UNIQUE INDEX uq_block_finality_canonical_height
    ON block_finality (chain_config_id, block_number)
    WHERE canonical;
CREATE INDEX idx_block_finality_incarnation_history
    ON block_finality (chain_config_id, block_number, observed_at, id);

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN EFFECT JOURNAL
-- ═══════════════════════════════════════════════════════════════════════════
-- The effect journal: one row per state change caused by an on-chain event, written in the same
-- transaction as the change it describes. Descriptor-primary — the dispatcher routes on
-- module_name + effect_type + entity_type + entity_id and never interprets before_state/
-- after_state; the owning module decides how to undo (RECOMPUTE re-derives, INVERSE_FLIP restores
-- a prior column value, IRREVERSIBLE only escalates). See finality.api.ChainEffectRecorder's
-- javadoc for the full rationale.
-- PostgreSQL CURRENT_TIMESTAMP is the transaction-start timestamp, so recorded_at cannot order
-- two effects written in one transaction. A sequence is monotonic for committed inserts (gaps on
-- rollback are harmless) and gives compensation sweeps an unambiguous LIFO order.
CREATE SEQUENCE chain_effect_journal_sequence_seq AS BIGINT;

CREATE TABLE chain_effect (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id     UUID          NOT NULL REFERENCES chain_config(id),
    block_number        BIGINT        NOT NULL,
    block_hash          VARCHAR(128),
    tx_hash             VARCHAR(128),
    log_index           INT,
    source_event_key    VARCHAR(512) NOT NULL,
    module_name         VARCHAR(40)   NOT NULL,
    effect_type         VARCHAR(60)   NOT NULL,
    entity_type         VARCHAR(60)   NOT NULL,
    entity_id           UUID          NOT NULL,
    category            VARCHAR(20)   NOT NULL,
    before_state        JSONB,
    after_state         JSONB,
    audit_event_id      UUID,
    correlation_id      UUID,
    status              VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    attempt_count        INT          NOT NULL DEFAULT 0,
    resolution_detail          TEXT,
    recorded_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    compensated_at      TIMESTAMPTZ,
    acknowledged_by     UUID,
    acknowledged_at     TIMESTAMPTZ,
    -- Drives the FinalityGate freeze check: while any chain_effect for asset X is unresolved (a failed or
    -- irreversible compensation), FinalityGate blocks every operation on X until an admin acknowledges
    -- with a reason.
    --
    -- asset_id is denormalised onto chain_effect (rather than resolved via entity_type/entity_id at
    -- gate-check time) because the finality module deliberately imports nothing but shared and
    -- audit.api — resolving entity_id back to an asset would require importing asset/deployment/
    -- vault/etc. Populated only where the recording module already has an assetId at hand (asset
    -- deployments, vault strikes/requests, blockchain transactions); left null for effect types that
    -- are not asset-scoped (org identity, ecosystem permissions, marketplace listings) — no
    -- GatedOperation gates those today, so this is not a coverage gap in practice.
    asset_id UUID,
    -- The reason an admin gave when unblocking an asset frozen by an unresolved compensation —
    -- acknowledged_by/acknowledged_at come with a reason column, like every other break-glass action in this
    -- codebase (e.g. finality_policy_override.reason).
    acknowledge_reason TEXT,
    -- Lets ChainEffectRepository#claimForCompensation reclaim a row stuck in COMPENSATING because the
    -- JVM that claimed it crashed mid-compensate — previously such a row was claimable only from
    -- ACTIVE/COMPENSATION_FAILED and would sit COMPENSATING forever.
    claimed_at TIMESTAMPTZ,
    journal_sequence BIGINT NOT NULL DEFAULT nextval('chain_effect_journal_sequence_seq'),
    CONSTRAINT chk_chain_effect_category CHECK (category IN ('RECOMPUTE', 'INVERSE_FLIP', 'IRREVERSIBLE')),
    CONSTRAINT chk_chain_effect_status CHECK (status IN (
        'ACTIVE', 'SETTLED', 'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED', 'IRREVERSIBLE_ESCALATED')),
    -- Idempotent recording: two dispatches of the same source event for the same entity collapse
    -- into one row (ON CONFLICT DO NOTHING on the write side, see ChainEffectRecorderImpl).
    CONSTRAINT uq_chain_effect_source UNIQUE (source_event_key, effect_type, entity_id),
    CONSTRAINT chk_chain_effect_normalized_hex_block_hash CHECK (
        block_hash IS NULL
        OR block_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR block_hash = lower(block_hash)
    ),
    CONSTRAINT chk_chain_effect_normalized_hex_tx_hash CHECK (
        tx_hash IS NULL
        OR tx_hash !~ '^0[xX][0-9A-Fa-f]+$'
        OR tx_hash = lower(tx_hash)
    ),
    CONSTRAINT uq_chain_effect_journal_sequence UNIQUE (journal_sequence)
);
ALTER SEQUENCE chain_effect_journal_sequence_seq OWNED BY chain_effect.journal_sequence;

-- The hot query: "which effects are still unresolved for entity X" (the FinalityGate freeze check,
-- a later phase) and "find this entity's effects for a given module".
CREATE INDEX idx_chain_effect_entity ON chain_effect (module_name, entity_type, entity_id);

-- The retry job's query: everything still ACTIVE or COMPENSATION_FAILED, oldest first.
CREATE INDEX idx_chain_effect_status ON chain_effect (status, recorded_at);

-- The gate's hot query: "does asset X have any unresolved (failed/irreversible, unacknowledged)
-- compensation". Partial index — only COMPENSATION_FAILED/IRREVERSIBLE_ESCALATED rows are ever
-- looked up this way, and most rows never reach those statuses.
CREATE INDEX idx_chain_effect_asset_unresolved ON chain_effect (asset_id)
    WHERE asset_id IS NOT NULL
      AND status IN ('COMPENSATION_FAILED', 'IRREVERSIBLE_ESCALATED')
      AND acknowledged_at IS NULL;
CREATE INDEX idx_chain_effect_chain_block_identity_sequence
    ON chain_effect (chain_config_id, block_hash, journal_sequence DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- FINALITY POLICY
-- ═══════════════════════════════════════════════════════════════════════════
-- The finality policy model: which FinalityLevel a GatedOperation requires before it is allowed
-- to proceed. Resolution chain (most specific wins, see FinalityPolicyResolverImpl): asset
-- override > asset-scoped assignment > token-standard-scoped assignment > global assignment >
-- compiled-in default (FinalityPolicyDefaults — code, not a seed row, so a fresh database, an
-- integration test, and a failed migration all behave identically).
CREATE TABLE finality_policy_assignment (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type      VARCHAR(20)   NOT NULL,
    token_standard  VARCHAR(30),
    asset_id        UUID          REFERENCES asset(id),
    profile         VARCHAR(20)   NOT NULL,
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_finality_policy_assignment_scope_type CHECK (scope_type IN ('GLOBAL', 'TOKEN_STANDARD', 'ASSET')),
    CONSTRAINT chk_finality_policy_assignment_profile CHECK (profile IN ('FAST', 'BALANCED', 'CONSERVATIVE')),
    -- Exactly one of token_standard/asset_id is set, matching scope_type — enforced here (not
    -- just in application code) since this table is small and directly operator-editable.
    CONSTRAINT chk_finality_policy_assignment_scope_shape CHECK (
        (scope_type = 'GLOBAL' AND token_standard IS NULL AND asset_id IS NULL) OR
        (scope_type = 'TOKEN_STANDARD' AND token_standard IS NOT NULL AND asset_id IS NULL) OR
        (scope_type = 'ASSET' AND asset_id IS NOT NULL AND token_standard IS NULL)
    )
);

-- At most one assignment per scope — partial unique indexes because a plain UNIQUE(scope_type,
-- token_standard, asset_id) would not work: Postgres treats each NULL as distinct, so it would not
-- actually stop two GLOBAL rows (both NULL/NULL) from coexisting.
CREATE UNIQUE INDEX uq_finality_policy_assignment_global ON finality_policy_assignment (scope_type) WHERE scope_type = 'GLOBAL';
CREATE UNIQUE INDEX uq_finality_policy_assignment_token_standard ON finality_policy_assignment (token_standard) WHERE scope_type = 'TOKEN_STANDARD';
CREATE UNIQUE INDEX uq_finality_policy_assignment_asset ON finality_policy_assignment (asset_id) WHERE scope_type = 'ASSET';

-- The audited, step-up-protected escape hatch — stays empty normally. One row per (asset,
-- operation): the most specific rung in the resolution chain, always wins over any assignment.
-- No expiry column: an override is removed by an explicit, audited delete, not left to lapse
-- silently.
CREATE TABLE finality_policy_override (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID          NOT NULL REFERENCES asset(id),
    operation       VARCHAR(50)   NOT NULL,
    required_level  VARCHAR(16)   NOT NULL,
    reason          TEXT          NOT NULL,
    created_by      UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_finality_policy_override_level CHECK (required_level IN ('PROVISIONAL', 'SAFE', 'FINALIZED')),
    CONSTRAINT uq_finality_policy_override UNIQUE (asset_id, operation)
);
CREATE INDEX idx_finality_policy_override_asset ON finality_policy_override (asset_id);

-- Block identity is append-only. Finality/canonical state may advance, but an incarnation can
-- never be rewritten to masquerade as a different block or height.
CREATE FUNCTION rw_reject_block_finality_identity_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.chain_config_id IS DISTINCT FROM OLD.chain_config_id
       OR NEW.block_number IS DISTINCT FROM OLD.block_number
       OR NEW.block_hash IS DISTINCT FROM OLD.block_hash THEN
        RAISE EXCEPTION 'block_finality incarnation identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_block_finality_immutable_identity
    BEFORE UPDATE OF chain_config_id, block_number, block_hash ON block_finality
    FOR EACH ROW
    EXECUTE FUNCTION rw_reject_block_finality_identity_change();

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAIN REORG EPISODE
-- ═══════════════════════════════════════════════════════════════════════════
-- Durable episode claim for Chaincache's typed reorg envelope. The unique key is the boundary
-- that makes replay after commit-before-ACK harmless: mutation and claim share one transaction.
CREATE TABLE chain_reorg_episode (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id         UUID         NOT NULL REFERENCES chain_config(id),
    reorg_id                VARCHAR(128) NOT NULL,
    schema_version          VARCHAR(16)  NOT NULL,
    severity                VARCHAR(32)  NOT NULL,
    common_ancestor_number  BIGINT,
    common_ancestor_hash    VARCHAR(128),
    episode                 JSONB        NOT NULL,
    observed_at             TIMESTAMPTZ  NOT NULL,
    applied_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_chain_reorg_episode UNIQUE (chain_config_id, reorg_id),
    CONSTRAINT chk_chain_reorg_severity CHECK (
        severity IN ('ROUTINE', 'FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY')
    )
);
CREATE INDEX idx_chain_reorg_episode_chain_observed
    ON chain_reorg_episode (chain_config_id, observed_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════
-- ACTIVE CHAIN QUARANTINE
-- ═══════════════════════════════════════════════════════════════════════════
-- Current fail-closed state for a chain whose canonical history cannot safely be mutated.
-- chain_reorg_episode remains the immutable incident journal; this row is the operational
-- snapshot consulted by FinalityGate and ingestion. Resolution fields are reserved for the
-- explicit, audited operator workflow rather than making quarantine self-clearing.
CREATE TABLE chain_quarantine (
    chain_config_id  UUID         PRIMARY KEY REFERENCES chain_config(id),
    reorg_id         VARCHAR(128) NOT NULL,
    severity         VARCHAR(32)  NOT NULL,
    observed_at      TIMESTAMPTZ  NOT NULL,
    activated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    resolved_at      TIMESTAMPTZ,
    trigger_reason VARCHAR(48) NOT NULL,
    trigger_detail TEXT,
    CONSTRAINT fk_chain_quarantine_episode
        FOREIGN KEY (chain_config_id, reorg_id)
        REFERENCES chain_reorg_episode(chain_config_id, reorg_id),
    CONSTRAINT chk_chain_quarantine_resolution
        CHECK ((active AND resolved_at IS NULL) OR (NOT active AND resolved_at IS NOT NULL)),
    CONSTRAINT chk_chain_quarantine_severity
            CHECK (severity IN ('ROUTINE', 'FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY')),
    CONSTRAINT chk_chain_quarantine_trigger CHECK (trigger_reason IN (
        'CONSENSUS_FINALITY_VIOLATION', 'UNRESOLVED_ANCESTRY', 'LOCAL_FINALITY_CONFLICT',
        'INDEXER_COMPENSATION_FAILED', 'DOMAIN_COMPENSATION_FAILED', 'REORG_ID_COLLISION'
    ))
);
CREATE INDEX idx_chain_quarantine_active
    ON chain_quarantine (chain_config_id) WHERE active = TRUE;


-- ═══════════════════════════════════════════════════════════════════════════
-- DURABLE EVM SUBMISSION
-- ═══════════════════════════════════════════════════════════════════════════
-- Persist exact signed bytes before RPC broadcast. Retrying a prepared row can only resubmit the
-- same sender/nonce/hash, closing the post-broadcast database-failure duplication window.
CREATE TABLE evm_signed_submission (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id  UUID          NOT NULL REFERENCES chain_config(id),
    chain_id          NUMERIC(78,0) NOT NULL,
    sender_address    VARCHAR(42)   NOT NULL,
    nonce             NUMERIC(78,0) NOT NULL,
    tx_hash           VARCHAR(66)   NOT NULL UNIQUE,
    signed_payload    TEXT          NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PREPARED',
    chain_name        VARCHAR(30)   NOT NULL,
    network           VARCHAR(30)   NOT NULL,
    contract_address  VARCHAR(42)   NOT NULL,
    method_name       VARCHAR(100)  NOT NULL,
    params             JSONB,
    actor_name        VARCHAR(255),
    actor_role        VARCHAR(30),
    attempt_count     INTEGER       NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    broadcast_at      TIMESTAMPTZ,
    -- Phase 4 K4b (P4B-4 / P4B-5, parked T4-03 interim): durable-outbox recovery and TIMEOUT semantics.
    --
    -- evm_signed_submission gains the states a stuck nonce needs (SUPERSEDED = a replacement at the same
    -- nonce exists, the original may still be mined; ABANDONED = the nonce is proven consumed by a
    -- different transaction), failure classification, back-off scheduling and operator evidence.
    -- The (chain_id, sender_address, nonce) uniqueness now only binds ACTIVE rows, otherwise a re-priced
    -- or cancelling replacement could never share the nonce of the transaction it replaces.
    first_failed_at       TIMESTAMPTZ,
    last_error_class      VARCHAR(30),
    next_attempt_at       TIMESTAMPTZ,
    kind                  VARCHAR(20)  NOT NULL DEFAULT 'OPERATION',
    replaces_tx_hash      VARCHAR(66),
    superseded_by_tx_hash VARCHAR(66),
    abandoned_at          TIMESTAMPTZ,
    abandoned_by          VARCHAR(255),
    abandon_approver_id   UUID,
    abandon_reason        TEXT,
    last_rebroadcast_at   TIMESTAMPTZ,
    rebroadcast_count     INTEGER      NOT NULL DEFAULT 0,
    stuck_alerted_at      TIMESTAMPTZ,
    -- 2. The key of the originating request is stored on the durable outbox row (and mirrored on
    --    blockchain_transaction), so a replay maps to the SAME signed transaction even after the cached HTTP
    --    response was purged (retention) or deleted (5xx). Value: '<scope>:<scopeId>:<key>#<n>', n = the
    --    ordinal of the submission within that request.
    idempotency_key VARCHAR(400),
    CONSTRAINT chk_evm_signed_submission_payload CHECK (signed_payload ~ '^0x[0-9a-fA-F]+$'),
    CONSTRAINT chk_evm_signed_submission_hash CHECK (tx_hash ~ '^0x[0-9a-fA-F]{64}$'),
    CONSTRAINT chk_evm_signed_submission_kind CHECK (kind IN ('OPERATION', 'REPRICE', 'CANCEL')),
    CONSTRAINT chk_evm_signed_submission_status
        CHECK (status IN ('PREPARED', 'BROADCAST', 'SUPERSEDED', 'ABANDONED')),
    CONSTRAINT chk_evm_signed_submission_broadcast CHECK (
        (status = 'PREPARED' AND broadcast_at IS NULL)
        OR (status = 'BROADCAST' AND broadcast_at IS NOT NULL)
        OR status IN ('SUPERSEDED', 'ABANDONED')
    )
);
CREATE UNIQUE INDEX uq_evm_signed_submission_active_nonce
    ON evm_signed_submission (chain_id, sender_address, nonce)
    WHERE status IN ('PREPARED', 'BROADCAST');
CREATE INDEX idx_evm_signed_submission_nonce ON evm_signed_submission (chain_id, sender_address, nonce);
CREATE INDEX idx_evm_signed_submission_pending
    ON evm_signed_submission (chain_id, sender_address, nonce) WHERE status = 'PREPARED';
CREATE INDEX idx_evm_signed_submission_broadcast
    ON evm_signed_submission (broadcast_at) WHERE status = 'BROADCAST';
CREATE UNIQUE INDEX ux_evm_signed_submission_idempotency_key
    ON evm_signed_submission (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- H10 (nonce lease repair vs. direct sends): ledger of the transactions the backend signs and broadcasts
-- OUTSIDE the durable outbox (immediate submit / send / deploy of EvmContractService: contract deployments,
-- suite and compliance-module management, ownership acceptance).
--
-- NonceCoordinator caps a stale nonce lease back to the chain's pending count after the lease has not moved for
-- a while. Until now the only thing that protected a nonce in the gap was an evm_signed_submission (outbox) row;
-- a direct send leaves no row, so when the failover read hit a lagging node the coordinator could hand out a
-- nonce a still-pending direct transaction already used and replace that transaction. Each direct send now
-- registers (chain, sender, nonce, tx hash) here BEFORE it is broadcast, under the same advisory lock that
-- serialises nonce hand-out, and lease repair refuses to reuse a nonce whose hash any RPC node still knows.
-- Several hashes per nonce are possible (a retry after an ambiguous broadcast failure), hence the hash in the key.
-- Rows are only a safety ledger: they are pruned by age (registerwerk.outbox.direct-ledger-retention).
CREATE TABLE evm_direct_submission (
    chain_id       BIGINT        NOT NULL,
    sender_address VARCHAR(42)   NOT NULL,
    nonce          NUMERIC(78,0) NOT NULL,
    tx_hash        VARCHAR(66)   NOT NULL,
    kind           VARCHAR(10)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, sender_address, tx_hash),
    CONSTRAINT chk_evm_direct_submission_kind CHECK (kind IN ('SUBMIT', 'SEND', 'DEPLOY')),
    CONSTRAINT chk_evm_direct_submission_hash CHECK (tx_hash ~ '^0x[0-9a-fA-F]{64}$')
);
CREATE INDEX idx_evm_direct_submission_nonce   ON evm_direct_submission (chain_id, sender_address, nonce);
CREATE INDEX idx_evm_direct_submission_created ON evm_direct_submission (created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- CHAINCACHE LIFECYCLE INBOX (EXACTLY-ONCE DELIVERY)
-- ═══════════════════════════════════════════════════════════════════════════
-- Registerwerk is an at-least-once Chaincache consumer.  These tables form the local
-- transactional inbox and occurrence ledger which turn redelivery into exactly-once effects.
CREATE TABLE chaincache_event_inbox (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    chain_key             VARCHAR(200) NOT NULL,
    source_sequence       BIGINT NOT NULL CHECK (source_sequence >= 0),
    event_id              VARCHAR(512) NOT NULL,
    schema_version        VARCHAR(20) NOT NULL,
    event_kind            VARCHAR(32) NOT NULL,
    finality              VARCHAR(16),
    payload_hash          VARCHAR(64) NOT NULL,
    raw_event             JSONB NOT NULL,
    processing_state      VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    delivery_count        BIGINT NOT NULL DEFAULT 1,
    first_received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_received_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at          TIMESTAMPTZ,
    last_error            TEXT,
    CONSTRAINT uq_chaincache_inbox_transport
        UNIQUE (durability_domain_id, chain_config_id, event_id),
    CONSTRAINT ck_chaincache_inbox_state
        CHECK (processing_state IN ('RECEIVED', 'PROCESSED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT ck_chaincache_inbox_finality
        CHECK (finality IS NULL OR finality IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED'))
);
CREATE INDEX idx_chaincache_inbox_sequence
    ON chaincache_event_inbox (durability_domain_id, chain_config_id, source_sequence);
CREATE INDEX idx_chaincache_inbox_unprocessed
    ON chaincache_event_inbox (chain_config_id, source_sequence)
    WHERE processing_state <> 'PROCESSED';

-- RetentionSweepJob#sweepChaincacheEventInbox's batch DELETE targets exactly this predicate.
CREATE INDEX idx_chaincache_inbox_processed_at
    ON chaincache_event_inbox (processed_at)
    WHERE processing_state = 'PROCESSED';

CREATE TABLE chain_contract_subscription (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    chain_key             VARCHAR(200) NOT NULL,
    consumer_id           VARCHAR(300) NOT NULL,
    last_sequence         BIGINT CHECK (last_sequence >= 0),
    last_event_id         VARCHAR(512),
    subscription_state    VARCHAR(20) NOT NULL DEFAULT 'LIVE',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chain_contract_subscription
        UNIQUE (durability_domain_id, chain_config_id, consumer_id),
    CONSTRAINT ck_chain_contract_subscription_state
        CHECK (subscription_state IN ('BOOTSTRAP', 'REPLAY', 'LIVE', 'QUARANTINED'))
);

CREATE TABLE chain_event_occurrence (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id       UUID NOT NULL REFERENCES chain_config(id),
    durability_domain_id  VARCHAR(200) NOT NULL,
    chain_key             VARCHAR(200) NOT NULL,
    block_number          BIGINT NOT NULL CHECK (block_number >= 0),
    block_hash            VARCHAR(128) NOT NULL,
    transaction_hash      VARCHAR(128) NOT NULL,
    transaction_index     INTEGER,
    log_index             INTEGER NOT NULL CHECK (log_index >= 0),
    contract_address      VARCHAR(128) NOT NULL,
    canonical_tenure      VARCHAR(200) NOT NULL DEFAULT '0',
    logical_event_id      VARCHAR(512) NOT NULL,
    first_event_id        VARCHAR(512) NOT NULL,
    last_event_id         VARCHAR(512) NOT NULL,
    current_finality      VARCHAR(16) NOT NULL,
    canonical             BOOLEAN NOT NULL DEFAULT TRUE,
    occurred_at           TIMESTAMPTZ NOT NULL,
    token_transfer_id     UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chain_event_occurrence UNIQUE
        (chain_config_id, block_hash, transaction_hash, log_index, contract_address, canonical_tenure),
    CONSTRAINT ck_chain_event_occurrence_finality
        CHECK (current_finality IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED')),
    CONSTRAINT ck_chain_event_occurrence_canonical
        CHECK ((canonical AND current_finality <> 'ORPHANED')
            OR (NOT canonical AND current_finality = 'ORPHANED'))
);
CREATE INDEX idx_chain_event_occurrence_logical_event
    ON chain_event_occurrence (durability_domain_id, chain_config_id, logical_event_id);
CREATE INDEX idx_chain_event_occurrence_block
    ON chain_event_occurrence (chain_config_id, block_number, block_hash);


-- ═══════════════════════════════════════════════════════════════════════════
-- RUNTIME LOGIN PRIVILEGES (registerwerk_app) — must stay the last section
-- ═══════════════════════════════════════════════════════════════════════════

GRANT USAGE ON SCHEMA public TO registerwerk_app;
-- No DDL for the runtime login. PUBLIC has no CREATE on public since PG 15; revoking explicitly also
-- covers clusters upgraded from older majors, where it would otherwise leak through PUBLIC.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM registerwerk_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO registerwerk_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO registerwerk_app;

-- Objects created by later migrations (run by the migrator) and by definer functions.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO registerwerk_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO registerwerk_app;

-- The blanket grant above includes UPDATE/DELETE; take them away again from the append-only audit tables
-- (audit_event and every partition, anchors, verification verdicts and their acknowledgements).
DO $$
DECLARE t REGCLASS;
BEGIN
    FOR t IN
        SELECT 'audit_event'::regclass
        UNION ALL SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'audit_event'::regclass
        UNION ALL SELECT 'audit_chain_anchor'::regclass
        UNION ALL SELECT 'audit_chain_verification'::regclass
        UNION ALL SELECT 'audit_chain_verification_ack'::regclass
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM registerwerk_app', t);
    END LOOP;
END
$$;

-- A read-only role for audit tooling: SELECT on the audit log and nothing else.
REVOKE ALL ON TABLE audit_event FROM registerwerk_audit_reader;
GRANT SELECT ON TABLE audit_event TO registerwerk_audit_reader;

-- Detaching/retiring partitions stays a migrator (operator) action: the application never calls it.
REVOKE EXECUTE ON FUNCTION rw_retire_partitions(regclass, text, int, text) FROM PUBLIC;
