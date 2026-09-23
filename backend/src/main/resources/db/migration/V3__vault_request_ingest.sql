-- ERC-7540 request ingestion (review finding T1-09). Nothing ever inserted vault_request rows:
-- investors submit requestDeposit/requestRedeem straight to the vault, so the operator
-- fulfil/cancel queue could never find a request. VaultRequestIngestionService now scans each
-- ERC-7540 deployment's finalized logs and upserts vault_request on (asset_id, request_id).

-- Who funded a deposit request (EwpgERC7540.depositRequestPayer) — cancel refunds go here, not
-- to the owner, so the operator's pre-cancel freeze check must look at this address.
ALTER TABLE vault_request ADD COLUMN payer_addr VARCHAR(80);

-- Exact occurrence of the DepositRequested/RedeemRequested log that created the row, so a
-- retraction of that block can be matched to this row (VAULT_REQUEST_INGESTED effect).
ALTER TABLE vault_request ADD COLUMN requested_tx VARCHAR(80);
ALTER TABLE vault_request ADD COLUMN requested_block_number BIGINT;
ALTER TABLE vault_request ADD COLUMN requested_block_hash VARCHAR(128);

-- Registry force-cancel (ForcedRequestCancelled): escrow destination and the stated legal basis.
ALTER TABLE vault_request ADD COLUMN forced_to_addr VARCHAR(80);
ALTER TABLE vault_request ADD COLUMN legal_basis VARCHAR(1000);

-- Set when a confirmed fulfilment could not be reconciled with its on-chain *Fulfilled event
-- (T1-08) — the row needs a human to look at it. NULL = nothing to review.
ALTER TABLE vault_request ADD COLUMN review_note VARCHAR(500);

-- Per-deployment log-scan cursor: the highest block whose vault logs have been ingested.
CREATE TABLE vault_request_ingest_cursor (
    asset_deployment_id UUID PRIMARY KEY REFERENCES asset_deployment(id),
    last_scanned_block  BIGINT NOT NULL,
    last_run_at         TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
