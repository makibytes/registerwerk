// ERC-3525 interface for Starknet (mirrors the EIP-3525 specification, including
// the metadata extension) plus the Registerwerk regulatory admin surface.

use starknet::ContractAddress;

#[starknet::interface]
pub trait IERC3525<TState> {
    // ── Metadata (ERC3525Metadata) ────────────────────────────────────────────
    fn name(self: @TState) -> felt252;
    fn symbol(self: @TState) -> felt252;
    fn value_decimals(self: @TState) -> u8;

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
    fn is_slot_paused(self: @TState, slot: u256) -> bool;
    fn set_slot_supply_cap(ref self: TState, slot: u256, cap: u256);
    fn slot_supply_cap(self: @TState, slot: u256) -> u256;
    fn slot_total_minted(self: @TState, slot: u256) -> u256;
    fn set_slot_metadata_hash(ref self: TState, slot: u256, metadata_hash: felt252);
    fn slot_metadata_hash(self: @TState, slot: u256) -> felt252;

    // ── Token-level admin ──────────────────────────────────────────────────────
    fn freeze_token(ref self: TState, token_id: u256, reason: felt252);
    fn unfreeze_token(ref self: TState, token_id: u256);
    fn is_token_frozen(self: @TState, token_id: u256) -> bool;
    fn forced_transfer_value(ref self: TState, from_token_id: u256, to_token_id: u256, value: u256, legal_basis: felt252);
    fn force_burn_value(ref self: TState, token_id: u256, value: u256, legal_basis: felt252);

    // ── Global pause (MiCAR Art. 36) ──────────────────────────────────────────
    fn pause(ref self: TState);
    fn unpause(ref self: TState);
    fn is_paused(self: @TState) -> bool;

    // ── Off-chain linkage ─────────────────────────────────────────────────────
    fn asset_id(self: @TState) -> u256;

    // ── Registry handover (two-step, key rotation / operator succession) ─────
    fn registry(self: @TState) -> ContractAddress;
    fn pending_registry(self: @TState) -> ContractAddress;
    fn transfer_registry(ref self: TState, new_registry: ContractAddress);
    fn cancel_registry_transfer(ref self: TState);
    fn accept_registry(ref self: TState);
}
