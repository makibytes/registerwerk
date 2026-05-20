// Minimal ERC-3525 interface for Starknet (mirrors the EIP-3525 specification).
// Full implementation is in the Carbonable base library (to be vendored).

use starknet::ContractAddress;

#[starknet::interface]
pub trait IERC3525<TState> {
    // ── ERC-721 base ──────────────────────────────────────────────────────────
    fn balance_of(self: @TState, owner: ContractAddress) -> u256;
    fn owner_of(self: @TState, token_id: u256) -> ContractAddress;
    fn transfer_from(ref self: TState, from: ContractAddress, to: ContractAddress, token_id: u256);

    // ── ERC-3525 specific ─────────────────────────────────────────────────────
    fn slot_of(self: @TState, token_id: u256) -> u256;
    fn balance_of_token(self: @TState, token_id: u256) -> u256;
    fn allowance(self: @TState, token_id: u256, operator: ContractAddress) -> u256;
    fn approve_value(ref self: TState, token_id: u256, operator: ContractAddress, value: u256);
    fn transfer_value_from(ref self: TState, from_token_id: u256, to_token_id: u256, value: u256);
    fn transfer_value_to(ref self: TState, from_token_id: u256, to: ContractAddress, value: u256) -> u256;
}

#[starknet::interface]
pub trait IEwpgERC3525Admin<TState> {
    // ── Slot-level admin (eWpG regulatory powers) ─────────────────────────────
    fn mint(ref self: TState, to: ContractAddress, slot: u256, value: u256) -> u256;
    fn pause_slot(ref self: TState, slot: u256);
    fn unpause_slot(ref self: TState, slot: u256);
    fn set_slot_supply_cap(ref self: TState, slot: u256, cap: u256);
    fn set_slot_metadata_hash(ref self: TState, slot: u256, metadata_hash: felt252);

    // ── Token-level admin ──────────────────────────────────────────────────────
    fn freeze_token(ref self: TState, token_id: u256, reason: felt252);
    fn unfreeze_token(ref self: TState, token_id: u256);
    fn forced_transfer_value(ref self: TState, from_token_id: u256, to_token_id: u256, value: u256, legal_basis: felt252);
    fn force_burn_value(ref self: TState, token_id: u256, value: u256, legal_basis: felt252);

    // ── Global pause (inherited from EwpgCompliance) ──────────────────────────
    fn pause(ref self: TState);
    fn unpause(ref self: TState);
    fn is_paused(self: @TState) -> bool;
}
