// EwpgERC3525 — eWpG-compliant semi-fungible token on Starknet.
//
// Extends the Carbonable Cairo ERC-3525 base (vendored into src/erc3525/base/) with:
//   - Slot-level pause / supply cap (bond series management)
//   - Token-level freeze (AWG §17 / GwG §40 sanctions freeze)
//   - Forced value transfer (eWpG §24 Berichtigung)
//   - Force burn value (eWpG §26 Einziehung)
//   - Global pause (MiCAR Art. 36)
//   - assetId link to off-chain Registerwerk asset record
//
// NOTE: This file is a skeleton. Before compiling, vendor the Carbonable ERC-3525 base:
//   cd contracts/cairo
//   git clone https://github.com/carbonable-labs/cairo-erc-3525.git .carbonable
//   cp -r .carbonable/src/erc3525/base src/erc3525/base
//
// Then replace the placeholder base import below with the actual Carbonable module path.

use starknet::{ContractAddress, get_caller_address};
use registerwerk_cairo::erc3525::interface::{IERC3525, IEwpgERC3525Admin};

// Events (mirroring the Solidity EwpgERC3525 event set for cross-chain consistency)
#[derive(Drop, starknet::Event)]
pub struct SlotPaused { pub slot: u256, pub by: ContractAddress }
#[derive(Drop, starknet::Event)]
pub struct SlotUnpaused { pub slot: u256 }
#[derive(Drop, starknet::Event)]
pub struct TokenFrozen { pub token_id: u256, pub reason: felt252 }
#[derive(Drop, starknet::Event)]
pub struct TokenUnfrozen { pub token_id: u256 }
#[derive(Drop, starknet::Event)]
pub struct ForcedValueTransfer { pub from_token_id: u256, pub to_token_id: u256, pub value: u256, pub legal_basis: felt252 }
#[derive(Drop, starknet::Event)]
pub struct ForcedValueBurn { pub token_id: u256, pub value: u256, pub legal_basis: felt252 }
#[derive(Drop, starknet::Event)]
pub struct SlotMetadataHashSet { pub slot: u256, pub metadata_hash: felt252 }

#[starknet::contract]
pub mod EwpgERC3525 {
    use super::*;
    use starknet::storage::{Map, StoragePointerReadAccess, StoragePointerWriteAccess, StorageMapReadAccess, StorageMapWriteAccess};

    #[storage]
    struct Storage {
        // Off-chain asset linkage
        asset_id_low: u128,
        asset_id_high: u128,

        // Registry admin (sole authority over all regulatory operations)
        registry_admin: ContractAddress,

        // Token counter
        next_token_id: u256,

        // Per-token data: slot and value
        token_slot: Map<u256, u256>,
        token_value: Map<u256, u256>,
        token_owner: Map<u256, ContractAddress>,

        // Compliance state
        global_paused: bool,
        slot_paused: Map<u256, bool>,
        slot_supply_cap: Map<u256, u256>,
        slot_total_minted: Map<u256, u256>,
        slot_metadata_hash: Map<u256, felt252>,
        token_frozen: Map<u256, bool>,

        // ERC-721 balance tracking
        balance: Map<ContractAddress, u256>,
    }

    #[event]
    #[derive(Drop, starknet::Event)]
    enum Event {
        SlotPaused: SlotPaused,
        SlotUnpaused: SlotUnpaused,
        TokenFrozen: TokenFrozen,
        TokenUnfrozen: TokenUnfrozen,
        ForcedValueTransfer: ForcedValueTransfer,
        ForcedValueBurn: ForcedValueBurn,
        SlotMetadataHashSet: SlotMetadataHashSet,
    }

    #[constructor]
    fn constructor(
        ref self: ContractState,
        registry_admin: ContractAddress,
        asset_id_low: u128,
        asset_id_high: u128,
    ) {
        self.registry_admin.write(registry_admin);
        self.asset_id_low.write(asset_id_low);
        self.asset_id_high.write(asset_id_high);
        self.next_token_id.write(1_u256);
    }

    #[abi(embed_v0)]
    impl EwpgERC3525AdminImpl of IEwpgERC3525Admin<ContractState> {
        fn mint(ref self: ContractState, to: ContractAddress, slot: u256, value: u256) -> u256 {
            self.only_registry();
            assert!(!self.slot_paused.read(slot), "EwpgERC3525: slot is paused");

            let cap = self.slot_supply_cap.read(slot);
            let minted = self.slot_total_minted.read(slot);
            if cap > 0_u256 {
                assert!(minted + value <= cap, "EwpgERC3525: slot supply cap exceeded");
            }
            self.slot_total_minted.write(slot, minted + value);

            let token_id = self.next_token_id.read();
            self.next_token_id.write(token_id + 1_u256);
            self.token_slot.write(token_id, slot);
            self.token_value.write(token_id, value);
            self.token_owner.write(token_id, to);
            self.balance.write(to, self.balance.read(to) + 1_u256);
            token_id
        }

        fn pause_slot(ref self: ContractState, slot: u256) {
            self.only_registry();
            self.slot_paused.write(slot, true);
            self.emit(Event::SlotPaused(SlotPaused { slot, by: get_caller_address() }));
        }

        fn unpause_slot(ref self: ContractState, slot: u256) {
            self.only_registry();
            self.slot_paused.write(slot, false);
            self.emit(Event::SlotUnpaused(SlotUnpaused { slot }));
        }

        fn set_slot_supply_cap(ref self: ContractState, slot: u256, cap: u256) {
            self.only_registry();
            self.slot_supply_cap.write(slot, cap);
        }

        fn set_slot_metadata_hash(ref self: ContractState, slot: u256, metadata_hash: felt252) {
            self.only_registry();
            self.slot_metadata_hash.write(slot, metadata_hash);
            self.emit(Event::SlotMetadataHashSet(SlotMetadataHashSet { slot, metadata_hash }));
        }

        fn freeze_token(ref self: ContractState, token_id: u256, reason: felt252) {
            self.only_registry();
            self.token_frozen.write(token_id, true);
            self.emit(Event::TokenFrozen(TokenFrozen { token_id, reason }));
        }

        fn unfreeze_token(ref self: ContractState, token_id: u256) {
            self.only_registry();
            self.token_frozen.write(token_id, false);
            self.emit(Event::TokenUnfrozen(TokenUnfrozen { token_id }));
        }

        fn forced_transfer_value(ref self: ContractState, from_token_id: u256, to_token_id: u256, value: u256, legal_basis: felt252) {
            self.only_registry();
            let from_slot = self.token_slot.read(from_token_id);
            let to_slot = self.token_slot.read(to_token_id);
            assert!(from_slot == to_slot, "EwpgERC3525: tokens in different slots");
            let from_balance = self.token_value.read(from_token_id);
            assert!(from_balance >= value, "EwpgERC3525: insufficient value");
            self.token_value.write(from_token_id, from_balance - value);
            self.token_value.write(to_token_id, self.token_value.read(to_token_id) + value);
            self.emit(Event::ForcedValueTransfer(ForcedValueTransfer { from_token_id, to_token_id, value, legal_basis }));
        }

        fn force_burn_value(ref self: ContractState, token_id: u256, value: u256, legal_basis: felt252) {
            self.only_registry();
            let current = self.token_value.read(token_id);
            assert!(current >= value, "EwpgERC3525: insufficient value");
            self.token_value.write(token_id, current - value);
            self.emit(Event::ForcedValueBurn(ForcedValueBurn { token_id, value, legal_basis }));
        }

        fn pause(ref self: ContractState) {
            self.only_registry();
            self.global_paused.write(true);
        }

        fn unpause(ref self: ContractState) {
            self.only_registry();
            self.global_paused.write(false);
        }

        fn is_paused(self: @ContractState) -> bool {
            self.global_paused.read()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    #[generate_trait]
    impl InternalImpl of InternalTrait {
        fn only_registry(self: @ContractState) {
            assert!(get_caller_address() == self.registry_admin.read(), "EwpgERC3525: caller is not registry");
        }
    }
}
