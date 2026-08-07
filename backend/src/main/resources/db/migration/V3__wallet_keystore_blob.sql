-- V3 — Postgres-backed wallet keystore storage.
--
-- WalletStorage previously wrote encrypted keystore/DEK-sidecar files only to a local
-- filesystem path (registerwerk.wallet.storage-dir, default /data/wallets). That makes the
-- backend node-affine: every replica needs the same files, so the Helm chart mounted one
-- ReadWriteOnce PVC into every pod — which cannot co-schedule with the chart's own required
-- pod anti-affinity (one replica per node). Replicas beyond the first hang on
-- FailedAttachVolume forever.
--
-- This table lets KeystoreBlobStore persist the same encrypted bytes (KEK-wrapped DEK +
-- ciphertext — nothing here is plaintext key material) in Postgres instead, which already
-- has its own HA/replication/backup story. Selected via
-- registerwerk.wallet.storage-backend=POSTGRES; the filesystem backend remains the default
-- for local/single-instance dev.

CREATE TABLE wallet_keystore_blob (
    relative_path VARCHAR(255) PRIMARY KEY,
    content       BYTEA NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
