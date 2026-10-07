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
