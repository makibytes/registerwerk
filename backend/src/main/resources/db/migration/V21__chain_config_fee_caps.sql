-- Per-chain EVM fee ceilings (Phase 4 K4a, P4B-2 / parked T4-02 interim).
-- NULL = use the global default (registerwerk.blockchain.fee-cap.default-max-fee-gwei /
-- default-max-tip-gwei). Values are in wei. EvmContractService refuses to sign a transaction whose
-- maxFeePerGas / maxPriorityFeePerGas (or legacy gasPrice) exceeds the applicable cap.
ALTER TABLE chain_config
    ADD COLUMN max_fee_per_gas_wei          NUMERIC(38,0) CHECK (max_fee_per_gas_wei IS NULL OR max_fee_per_gas_wei > 0),
    ADD COLUMN max_priority_fee_per_gas_wei NUMERIC(38,0) CHECK (max_priority_fee_per_gas_wei IS NULL OR max_priority_fee_per_gas_wei > 0);
