-- Adds the AWAITING_SELLER_CONFIRMATION settlement status: a buyer declaring payment no longer
-- credits the register directly — the selling company must independently confirm receipt first.
ALTER TABLE trade_execution ADD COLUMN payment_declared_at TIMESTAMPTZ;

ALTER TABLE trade_execution ALTER COLUMN settlement_status TYPE VARCHAR(30);

ALTER TABLE trade_execution DROP CONSTRAINT chk_trade_execution_settlement_status;
ALTER TABLE trade_execution ADD CONSTRAINT chk_trade_execution_settlement_status CHECK (
    settlement_status IN ('PENDING','AWAITING_SELLER_CONFIRMATION','SETTLED','FAILED','CANCELLED','REFUNDED')
);

CREATE INDEX idx_trade_execution_awaiting_confirmation ON trade_execution (payment_declared_at)
    WHERE settlement_status = 'AWAITING_SELLER_CONFIRMATION';
