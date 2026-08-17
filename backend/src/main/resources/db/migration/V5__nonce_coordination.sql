-- ═══════════════════════════════════════════════════════════════════════════
-- V5 — Production-readiness Phase 3: cross-instance-safe EVM nonce coordination.
--
-- EvmContractService's own Javadoc (before this migration) documented the gap directly:
-- "Single-instance only. These locks live in this JVM. With more than one backend replica
-- submitting from the same operator wallet, two instances can read the same pending nonce and
-- one transaction silently replaces the other." The Helm chart (deploy/helm/registerwerk/
-- values.yaml) ships replicaCount 3 + HPA to 10 against exactly this signing path, so this is a
-- live correctness bug at the shipped configuration, not a theoretical one.
--
-- wallet_nonce_lease is a durable, per-(evm-chain-id, sender-address) "next nonce to use"
-- counter, read/written under a Postgres advisory lock (blockchain.internal.NonceCoordinator)
-- that is held for the full nonce-decide -> sign -> broadcast critical section, replacing the
-- previous per-JVM `synchronized` block with a fleet-wide one. The lease is deliberately NOT the
-- sole source of truth: on every use it is reconciled against a fresh on-chain
-- eth_getTransactionCount(..., PENDING) read and the higher of the two wins (see
-- NonceCoordinator's class Javadoc for why) — so a lease that was never initialized, or that
-- fell behind reality (e.g. a wallet used out-of-band, or the row was manually cleared), always
-- self-heals to the chain's own view rather than risking nonce reuse.
--
-- Keyed on the numeric EVM chain_id (EIP-155), not chain_config.id: nonces are a property of the
-- (sender address, chain) pair on the real network, independent of however many internal
-- chain_config rows this application happens to have for it, and EvmContractService already
-- resolves/caches eth_chainId per Web3j client for EIP-155 signing — reusing it here means every
-- one of the 25 existing evmContractService.submit/send/deploy call sites needs zero changes to
-- pass a new identifier through. No foreign key to chain_config on purpose: nonce coordination
-- must not depend on a chain_config row's lifecycle.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE wallet_nonce_lease (
    chain_id       BIGINT        NOT NULL,
    sender_address VARCHAR(42)   NOT NULL,
    -- NUMERIC(78,0): large enough for any uint256 nonce value, matching the precision used
    -- elsewhere in this schema for on-chain integers (e.g. token_transfer.amount).
    next_nonce     NUMERIC(78,0) NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, sender_address)
);

COMMENT ON TABLE wallet_nonce_lease IS
    'Durable next-nonce counter per (chain_id, sender_address), coordinated across backend '
    'replicas via a Postgres advisory lock (NonceCoordinator). Advanced only after a transaction '
    'is successfully broadcast (eth_sendRawTransaction returns without error) — a failed '
    'submission leaves the row untouched so the same nonce is correctly retried next time, '
    'exactly matching the pre-existing single-instance behavior of always re-reading the pending '
    'chain nonce on every attempt.';
