-- Immutable EwpgRepoMarket parameters verified from chain at registration time. Existing rows
-- remain nullable and therefore fail closed for new borrowing until an operator re-registers or
-- reconciles them against the deployed contract; inventing a max-LTV during migration would be
-- materially unsafe.
ALTER TABLE lending_market
    ADD COLUMN max_ltv_bps INTEGER,
    ADD COLUMN max_price_age_seconds NUMERIC(78,0),
    ADD COLUMN liquidation_grace_period_seconds NUMERIC(78,0),
    ADD COLUMN loan_token_decimals INTEGER;

ALTER TABLE lending_market
    ADD CONSTRAINT chk_lending_market_max_ltv
        CHECK (max_ltv_bps IS NULL OR (max_ltv_bps > 0 AND max_ltv_bps < lltv_bps)),
    ADD CONSTRAINT chk_lending_market_loan_decimals
        CHECK (loan_token_decimals IS NULL OR (loan_token_decimals >= 0 AND loan_token_decimals <= 36));
