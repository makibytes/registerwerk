// EwpgERC20 interface — fungible eWpG security token on Starknet.
// Mirrors the Solidity EwpgERC20 + EwpgCompliance surface for cross-chain consistency.

use starknet::ContractAddress;

#[starknet::interface]
pub trait IEwpgERC20<TState> {
    // ── ERC-20 standard ───────────────────────────────────────────────────────
    fn name(self: @TState) -> felt252;
    fn symbol(self: @TState) -> felt252;
    fn decimals(self: @TState) -> u8;
    fn total_supply(self: @TState) -> u256;
    fn balance_of(self: @TState, account: ContractAddress) -> u256;
    fn allowance(self: @TState, owner: ContractAddress, spender: ContractAddress) -> u256;
    fn transfer(ref self: TState, to: ContractAddress, amount: u256) -> bool;
    fn transfer_from(ref self: TState, from: ContractAddress, to: ContractAddress, amount: u256) -> bool;
    fn approve(ref self: TState, spender: ContractAddress, amount: u256) -> bool;
}

#[starknet::interface]
pub trait IEwpgERC20Admin<TState> {
    // ── Issuance (registry only) ──────────────────────────────────────────────
    fn mint(ref self: TState, to: ContractAddress, amount: u256);
    fn burn(ref self: TState, from: ContractAddress, amount: u256);

    // ── Whitelist (IEwpgCompliant) ────────────────────────────────────────────
    fn whitelist(ref self: TState, account: ContractAddress);
    fn remove_from_whitelist(ref self: TState, account: ContractAddress);
    fn is_whitelisted(self: @TState, account: ContractAddress) -> bool;

    // ── Pause / freeze (MiCAR Art. 36, 84; AWG §17, GwG §40) ─────────────────
    fn pause(ref self: TState);
    fn unpause(ref self: TState);
    fn is_paused(self: @TState) -> bool;
    fn freeze_address(ref self: TState, account: ContractAddress, reason: felt252);
    fn unfreeze_address(ref self: TState, account: ContractAddress);
    fn is_frozen(self: @TState, account: ContractAddress) -> bool;

    // ── Supply cap (MiCAR Art. 46) ────────────────────────────────────────────
    fn set_supply_cap(ref self: TState, new_cap: u256);
    fn supply_cap(self: @TState) -> u256;

    // ── Regulatory overrides (eWpG §24 Berichtigung, §26 Einziehung) ─────────
    fn forced_transfer(ref self: TState, from: ContractAddress, to: ContractAddress, amount: u256, legal_basis: felt252);
    fn forced_approve(ref self: TState, owner: ContractAddress, spender: ContractAddress, amount: u256, legal_basis: felt252);
    fn force_burn(ref self: TState, from: ContractAddress, amount: u256, legal_basis: felt252);

    // ── Registry handover (two-step, key rotation / operator succession) ─────
    fn registry(self: @TState) -> ContractAddress;
    fn pending_registry(self: @TState) -> ContractAddress;
    fn transfer_registry(ref self: TState, new_registry: ContractAddress);
    fn cancel_registry_transfer(ref self: TState);
    fn accept_registry(ref self: TState);
}
