-- Microsoft Entra ID identity mapping.
--
-- Until now, an Entra-authenticated user's app_user row was created with a DB-generated UUID
-- and the Entra object id (the token's `oid`) was never recorded. That made app_user.id and
-- the token's `sub` two unrelated values, so SecurityUtils.extractUserId() returned an id that
-- matched no row: step-up token issuance failed, dual-control self-approval checks compared
-- the wrong subjects, and every audit_event actor_id was wrong. entra_object_id closes that gap.

ALTER TABLE app_user ADD COLUMN entra_object_id UUID;

COMMENT ON COLUMN app_user.entra_object_id IS
    'Entra object id (token `oid`). Stable per user per tenant; the join key between an Entra '
    'principal and this row. NULL for LOCAL accounts.';

-- Partial unique index rather than a UNIQUE constraint: every LOCAL account leaves this NULL,
-- and Postgres treats NULLs as distinct, but a partial index states the intent explicitly and
-- keeps the index off the many LOCAL rows.
CREATE UNIQUE INDEX ux_app_user_entra_object_id
    ON app_user (entra_object_id)
    WHERE entra_object_id IS NOT NULL;

-- Home tenant of the principal (token `tid`). When it differs from our own tenant the user is
-- federated from a customer's tenant, and we can neither read nor manage their MFA methods.
ALTER TABLE app_user ADD COLUMN entra_tenant_id UUID;

-- Advisory cache of the Microsoft Graph second-factor lookup, so the nav banner does not cost a
-- Graph round-trip on every page load. NEVER an authorisation input: Conditional Access is the
-- enforcement point, and a stale cache must not be able to grant or deny access.
ALTER TABLE app_user ADD COLUMN entra_mfa_registered_at TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN entra_mfa_checked_at    TIMESTAMPTZ;

-- Per-legal-entity identity model: whether the operator invites this customer's users as B2B
-- guests into its own tenant (and therefore manages their MFA), or federates to the customer's
-- own Entra tenant (and therefore does not). This column records operator *intent*; once a user
-- has actually signed in, app_user.entra_tenant_id is the ground truth.
ALTER TABLE legal_entity ADD COLUMN identity_model VARCHAR(20) NOT NULL DEFAULT 'WORKFORCE_GUEST';

ALTER TABLE legal_entity ADD CONSTRAINT chk_legal_entity_identity_model
    CHECK (identity_model IN ('WORKFORCE_MEMBER', 'WORKFORCE_GUEST', 'FEDERATED'));

ALTER TABLE legal_entity ADD COLUMN idp_tenant_id UUID;

-- Whether MFA performed in the customer's home tenant is trusted here (Entra cross-tenant
-- access settings). Operator-controlled only: a customer self-asserting "trust my MFA" would be
-- a privilege-escalation vector.
ALTER TABLE legal_entity ADD COLUMN idp_mfa_trusted BOOLEAN NOT NULL DEFAULT FALSE;

-- Drop the stored IdP client secret. It was written in plaintext and is structurally
-- unnecessary: inbound B2B federation is configured tenant-to-tenant in the Entra portal, and
-- Registerwerk never runs an authorization-code flow against a customer's tenant, so it has no
-- use for their client secret. The column itself stays (V1 is shipped and must not be edited);
-- nothing writes it from here on.
UPDATE legal_entity SET idp_client_secret = NULL WHERE idp_client_secret IS NOT NULL;
