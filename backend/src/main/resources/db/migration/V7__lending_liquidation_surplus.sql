-- EwpgRepoMarket credits a liquidated borrower with loan-token cash when a liquidator's payment
-- for whole collateral units exceeds the debt it closes (surplusOf / claimLiquidationSurplus).
-- The position read-model caches it so the customer "My Loans" view can offer the claim.
ALTER TABLE lending_position
    ADD COLUMN liquidation_surplus NUMERIC(78,0) NOT NULL DEFAULT 0;
