-- Phase 4 K7 (P4B-7): mandatory idempotency for money-moving admin/issuer APIs.
--
-- 1. idempotency_record was keyed by (entity_id, key) and the filter skipped every principal without an
--    entity_id claim, i.e. every operator/REGISTRY_ADMIN token. The row now carries a scope: 'ENTITY' (the
--    tenant, entity_id = legal entity) or 'USER' (entity_id = the JWT subject / user id, for operator tokens).
--    Existing rows are all ENTITY scoped.
ALTER TABLE idempotency_record ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'ENTITY';
ALTER TABLE idempotency_record ADD CONSTRAINT chk_idempotency_record_scope CHECK (scope IN ('ENTITY', 'USER'));
-- migration-safety: ack (replaced immediately below by a strictly weaker unique key; existing rows stay valid)
ALTER TABLE idempotency_record DROP CONSTRAINT uq_idempotency_record_entity_key;
ALTER TABLE idempotency_record ADD CONSTRAINT uq_idempotency_record_scope_key UNIQUE (scope, entity_id, idempotency_key);

COMMENT ON COLUMN idempotency_record.entity_id IS
    'Scope id: the legal entity for scope ENTITY, the acting user (JWT sub) for scope USER.';

-- 2. The key of the originating request is stored on the durable outbox row (and mirrored on
--    blockchain_transaction), so a replay maps to the SAME signed transaction even after the cached HTTP
--    response was purged (retention) or deleted (5xx). Value: '<scope>:<scopeId>:<key>#<n>', n = the
--    ordinal of the submission within that request.
ALTER TABLE evm_signed_submission ADD COLUMN idempotency_key VARCHAR(400);
CREATE UNIQUE INDEX ux_evm_signed_submission_idempotency_key
    ON evm_signed_submission (idempotency_key) WHERE idempotency_key IS NOT NULL;

ALTER TABLE blockchain_transaction ADD COLUMN idempotency_key VARCHAR(400);
CREATE INDEX idx_blockchain_transaction_idempotency_key
    ON blockchain_transaction (idempotency_key) WHERE idempotency_key IS NOT NULL;
