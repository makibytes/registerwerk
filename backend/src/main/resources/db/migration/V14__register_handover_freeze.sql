-- T3-07 register handover freeze, T3-11 inspection claim verification, T3-14 erasure vs. §19 delivery.

-- The asset status CHECK never listed TRANSFERRED_OUT, so RegisterTransferService.complete() would
-- have failed on the constraint; add it together with the new TRANSFER_PENDING freeze status.
ALTER TABLE asset DROP CONSTRAINT chk_asset_status;
ALTER TABLE asset ADD CONSTRAINT chk_asset_status CHECK (
    status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ISSUED','SUSPENDED','REDEEMED',
               'TRANSFER_PENDING','TRANSFERRED_OUT')
);

ALTER TABLE register_transfer ADD COLUMN previous_asset_status    VARCHAR(30);
-- Hash over the register content only (no exportedAt envelope), re-checked at completion.
ALTER TABLE register_transfer ADD COLUMN register_content_hash    VARCHAR(66);
-- Successor's on-chain registry/owner address, verified against each EVM deployment at handover.
ALTER TABLE register_transfer ADD COLUMN successor_onchain_address VARCHAR(42);
-- Per-deployment handover records: [{deploymentId, chain, txHash, verified, method, ...}]
ALTER TABLE register_transfer ADD COLUMN onchain_handovers        JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Rows created before this migration were auto-approved on a self-declared basis: claim_verified
-- stays FALSE for them (the honest default).
ALTER TABLE register_inspection_request ADD COLUMN claim_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE register_statement ADD COLUMN delivery_error_code VARCHAR(40);

ALTER TABLE erasure_request ADD COLUMN retained_notice_channel TEXT;
