// EwpgERC3525 — eWpG-compliant semi-fungible token (slot + value) on Starknet.
//
// Self-contained implementation of the EIP-3525 surface declared in
// interface.cairo, extended with the Registerwerk regulatory controls:
//   - Slot-level pause / supply cap (bond series management)
//   - Token-level freeze (AWG §17 / GwG §40 sanctions freeze)
//   - Forced value transfer (eWpG §24 Berichtigung)
//   - Force burn value (eWpG §26 Einziehung)
//   - Global pause (MiCAR Art. 36)
//   - Two-step registry handover (key rotation / operator succession)
//   - asset_id link to the off-chain Registerwerk asset record
//
// Constructor calldata matches the backend StarknetTokenService builder:
//   (name: felt252, symbol: felt252, value_decimals: u8,
//    registry_admin: ContractAddress, asset_id: u256)  — six felts on the wire.
//
// Design notes:
//   - ERC-721 operator approvals are intentionally not implemented in v1;
//     whole-token transfer_from is owner-only. Value-level approvals
//     (approve_value / transfer_value_from) cover the EIP-3525 delegation case.
//   - Forced operations bypass pause/slot-pause/freeze by design — they execute
//     BaFin / court orders and are gated to the registry wallet.

use starknet::ContractAddress;

// ── Events (mirroring the Solidity EwpgERC3525 event set) ────────────────────

#[derive(Drop, starknet::Event)]
pub struct Transfer {
    #[key]
    pub from: ContractAddress,
    #[key]
    pub to: ContractAddress,
    #[key]
    pub token_id: u256,
}

#[derive(Drop, starknet::Event)]
pub struct TransferValue {
    #[key]
    pub from_token_id: u256,
    #[key]
    pub to_token_id: u256,
    pub value: u256,
}

#[derive(Drop, starknet::Event)]
pub struct ApprovalValue {
    #[key]
    pub token_id: u256,
    #[key]
    pub operator: ContractAddress,
    pub value: u256,
}

#[derive(Drop, starknet::Event)]
pub struct SlotPaused {
    pub slot: u256,
    pub by: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct SlotUnpaused {
    pub slot: u256,
}

#[derive(Drop, starknet::Event)]
pub struct SlotSupplyCapSet {
    pub slot: u256,
    pub cap: u256,
}

#[derive(Drop, starknet::Event)]
pub struct TokenFrozen {
    pub token_id: u256,
    pub reason: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct TokenUnfrozen {
    pub token_id: u256,
}

#[derive(Drop, starknet::Event)]
pub struct ForcedValueTransfer {
    pub from_token_id: u256,
    pub to_token_id: u256,
    pub value: u256,
    pub legal_basis: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct ForcedValueBurn {
    pub token_id: u256,
    pub value: u256,
    pub legal_basis: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct SlotMetadataHashSet {
    pub slot: u256,
    pub metadata_hash: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct GlobalPaused {
    pub by: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct GlobalUnpaused {
    pub by: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct RegistryTransferStarted {
    #[key]
    pub current_registry: ContractAddress,
    #[key]
    pub pending_registry: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct RegistryTransferred {
    #[key]
    pub previous_registry: ContractAddress,
    #[key]
    pub new_registry: ContractAddress,
}

#[starknet::contract]
pub mod EwpgERC3525 {
    use core::num::traits::Zero;
    use starknet::{ContractAddress, get_caller_address};
    use starknet::storage::{
        Map, StoragePointerReadAccess, StoragePointerWriteAccess, StorageMapReadAccess,
        StorageMapWriteAccess,
    };
    use registerwerk_cairo::erc3525::interface::{IERC3525, IEwpgERC3525Admin};
    use super::{
        Transfer, TransferValue, ApprovalValue, SlotPaused, SlotUnpaused, SlotSupplyCapSet,
        TokenFrozen, TokenUnfrozen, ForcedValueTransfer, ForcedValueBurn, SlotMetadataHashSet,
        GlobalPaused, GlobalUnpaused, RegistryTransferStarted, RegistryTransferred,
    };

    #[storage]
    struct Storage {
        // Metadata
        name: felt252,
        symbol: felt252,
        value_decimals: u8,
        // Off-chain asset linkage
        asset_id: u256,
        // Registry authority + two-step handover
        registry_admin: ContractAddress,
        pending_registry: ContractAddress,
        // Token counter (token ids start at 1)
        next_token_id: u256,
        // Per-token data: slot, value, owner
        token_slot: Map<u256, u256>,
        token_value: Map<u256, u256>,
        token_owner: Map<u256, ContractAddress>,
        // Value-level allowances per EIP-3525: (token_id, operator) -> value
        value_allowances: Map<(u256, ContractAddress), u256>,
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
        Transfer: Transfer,
        TransferValue: TransferValue,
        ApprovalValue: ApprovalValue,
        SlotPaused: SlotPaused,
        SlotUnpaused: SlotUnpaused,
        SlotSupplyCapSet: SlotSupplyCapSet,
        TokenFrozen: TokenFrozen,
        TokenUnfrozen: TokenUnfrozen,
        ForcedValueTransfer: ForcedValueTransfer,
        ForcedValueBurn: ForcedValueBurn,
        SlotMetadataHashSet: SlotMetadataHashSet,
        GlobalPaused: GlobalPaused,
        GlobalUnpaused: GlobalUnpaused,
        RegistryTransferStarted: RegistryTransferStarted,
        RegistryTransferred: RegistryTransferred,
    }

    #[constructor]
    fn constructor(
        ref self: ContractState,
        name: felt252,
        symbol: felt252,
        value_decimals: u8,
        registry_admin: ContractAddress,
        asset_id: u256,
    ) {
        assert!(!registry_admin.is_zero(), "EwpgERC3525: zero registry address");
        self.name.write(name);
        self.symbol.write(symbol);
        self.value_decimals.write(value_decimals);
        self.registry_admin.write(registry_admin);
        self.asset_id.write(asset_id);
        self.next_token_id.write(1_u256);
    }

    // ── EIP-3525 investor surface ─────────────────────────────────────────────

    #[abi(embed_v0)]
    impl ERC3525Impl of IERC3525<ContractState> {
        fn name(self: @ContractState) -> felt252 {
            self.name.read()
        }

        fn symbol(self: @ContractState) -> felt252 {
            self.symbol.read()
        }

        fn value_decimals(self: @ContractState) -> u8 {
            self.value_decimals.read()
        }

        fn balance_of(self: @ContractState, owner: ContractAddress) -> u256 {
            self.balance.read(owner)
        }

        fn owner_of(self: @ContractState, token_id: u256) -> ContractAddress {
            let owner = self.token_owner.read(token_id);
            assert!(!owner.is_zero(), "EwpgERC3525: invalid token id");
            owner
        }

        /// Whole-token transfer. Owner-only in v1 (no ERC-721 operator approvals);
        /// the registry uses forced operations instead of this path.
        fn transfer_from(
            ref self: ContractState, from: ContractAddress, to: ContractAddress, token_id: u256,
        ) {
            let owner = self.token_owner.read(token_id);
            assert!(!owner.is_zero(), "EwpgERC3525: invalid token id");
            assert!(owner == from, "EwpgERC3525: from is not the owner");
            assert!(get_caller_address() == owner, "EwpgERC3525: caller is not the owner");
            assert!(!to.is_zero(), "EwpgERC3525: transfer to zero address");
            self.require_token_transferable(token_id);

            self.token_owner.write(token_id, to);
            self.balance.write(from, self.balance.read(from) - 1_u256);
            self.balance.write(to, self.balance.read(to) + 1_u256);
            self.emit(Event::Transfer(Transfer { from, to, token_id }));
        }

        fn slot_of(self: @ContractState, token_id: u256) -> u256 {
            assert!(!self.token_owner.read(token_id).is_zero(), "EwpgERC3525: invalid token id");
            self.token_slot.read(token_id)
        }

        fn balance_of_token(self: @ContractState, token_id: u256) -> u256 {
            assert!(!self.token_owner.read(token_id).is_zero(), "EwpgERC3525: invalid token id");
            self.token_value.read(token_id)
        }

        fn allowance(self: @ContractState, token_id: u256, operator: ContractAddress) -> u256 {
            self.value_allowances.read((token_id, operator))
        }

        fn approve_value(
            ref self: ContractState, token_id: u256, operator: ContractAddress, value: u256,
        ) {
            let owner = self.token_owner.read(token_id);
            assert!(!owner.is_zero(), "EwpgERC3525: invalid token id");
            assert!(get_caller_address() == owner, "EwpgERC3525: caller is not the owner");
            assert!(!operator.is_zero(), "EwpgERC3525: zero operator address");
            self.value_allowances.write((token_id, operator), value);
            self.emit(Event::ApprovalValue(ApprovalValue { token_id, operator, value }));
        }

        /// Moves `value` between two existing tokens of the same slot.
        /// Caller must own the source token or hold a sufficient value allowance.
        fn transfer_value_from(
            ref self: ContractState, from_token_id: u256, to_token_id: u256, value: u256,
        ) {
            let caller = get_caller_address();
            let owner = self.token_owner.read(from_token_id);
            assert!(!owner.is_zero(), "EwpgERC3525: invalid token id");
            if caller != owner {
                let current = self.value_allowances.read((from_token_id, caller));
                assert!(current >= value, "EwpgERC3525: insufficient value allowance");
                self.value_allowances.write((from_token_id, caller), current - value);
            }
            self.compliant_value_move(from_token_id, to_token_id, value);
        }

        /// Carves `value` out of `from_token_id` into a freshly minted token owned
        /// by `to` (same slot), per EIP-3525 semantics. Returns the new token id.
        fn transfer_value_to(
            ref self: ContractState, from_token_id: u256, to: ContractAddress, value: u256,
        ) -> u256 {
            let caller = get_caller_address();
            let owner = self.token_owner.read(from_token_id);
            assert!(!owner.is_zero(), "EwpgERC3525: invalid token id");
            assert!(!to.is_zero(), "EwpgERC3525: transfer to zero address");
            if caller != owner {
                let current = self.value_allowances.read((from_token_id, caller));
                assert!(current >= value, "EwpgERC3525: insufficient value allowance");
                self.value_allowances.write((from_token_id, caller), current - value);
            }
            let slot = self.token_slot.read(from_token_id);
            let new_token_id = self.create_token(to, slot);
            self.compliant_value_move(from_token_id, new_token_id, value);
            new_token_id
        }
    }

    // ── Registry admin surface ─────────────────────────────────────────────────

    #[abi(embed_v0)]
    impl EwpgERC3525AdminImpl of IEwpgERC3525Admin<ContractState> {
        fn mint(ref self: ContractState, to: ContractAddress, slot: u256, value: u256) -> u256 {
            self.only_registry();
            assert!(!to.is_zero(), "EwpgERC3525: mint to zero address");
            assert!(!self.global_paused.read(), "EwpgERC3525: transfers are paused");
            assert!(!self.slot_paused.read(slot), "EwpgERC3525: slot is paused");

            let cap = self.slot_supply_cap.read(slot);
            let minted = self.slot_total_minted.read(slot);
            if cap > 0_u256 {
                assert!(minted + value <= cap, "EwpgERC3525: slot supply cap exceeded");
            }
            self.slot_total_minted.write(slot, minted + value);

            let token_id = self.create_token(to, slot);
            self.token_value.write(token_id, value);
            self.emit(Event::TransferValue(TransferValue {
                from_token_id: 0_u256, to_token_id: token_id, value,
            }));
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

        fn is_slot_paused(self: @ContractState, slot: u256) -> bool {
            self.slot_paused.read(slot)
        }

        fn set_slot_supply_cap(ref self: ContractState, slot: u256, cap: u256) {
            self.only_registry();
            self.slot_supply_cap.write(slot, cap);
            self.emit(Event::SlotSupplyCapSet(SlotSupplyCapSet { slot, cap }));
        }

        fn slot_supply_cap(self: @ContractState, slot: u256) -> u256 {
            self.slot_supply_cap.read(slot)
        }

        fn slot_total_minted(self: @ContractState, slot: u256) -> u256 {
            self.slot_total_minted.read(slot)
        }

        fn set_slot_metadata_hash(ref self: ContractState, slot: u256, metadata_hash: felt252) {
            self.only_registry();
            self.slot_metadata_hash.write(slot, metadata_hash);
            self.emit(Event::SlotMetadataHashSet(SlotMetadataHashSet { slot, metadata_hash }));
        }

        fn slot_metadata_hash(self: @ContractState, slot: u256) -> felt252 {
            self.slot_metadata_hash.read(slot)
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

        fn is_token_frozen(self: @ContractState, token_id: u256) -> bool {
            self.token_frozen.read(token_id)
        }

        // Forced value transfer — eWpG §24. Bypasses pause/slot-pause/freeze.
        fn forced_transfer_value(
            ref self: ContractState,
            from_token_id: u256,
            to_token_id: u256,
            value: u256,
            legal_basis: felt252,
        ) {
            self.only_registry();
            let from_slot = self.token_slot.read(from_token_id);
            let to_slot = self.token_slot.read(to_token_id);
            assert!(!self.token_owner.read(from_token_id).is_zero(), "EwpgERC3525: invalid token id");
            assert!(!self.token_owner.read(to_token_id).is_zero(), "EwpgERC3525: invalid token id");
            assert!(from_slot == to_slot, "EwpgERC3525: tokens in different slots");
            let from_balance = self.token_value.read(from_token_id);
            assert!(from_balance >= value, "EwpgERC3525: insufficient value");
            self.token_value.write(from_token_id, from_balance - value);
            self.token_value.write(to_token_id, self.token_value.read(to_token_id) + value);
            self.emit(Event::TransferValue(TransferValue { from_token_id, to_token_id, value }));
            self.emit(Event::ForcedValueTransfer(ForcedValueTransfer {
                from_token_id, to_token_id, value, legal_basis,
            }));
        }

        // Force burn value — eWpG §26. Bypasses pause/slot-pause/freeze.
        fn force_burn_value(
            ref self: ContractState, token_id: u256, value: u256, legal_basis: felt252,
        ) {
            self.only_registry();
            let current = self.token_value.read(token_id);
            assert!(current >= value, "EwpgERC3525: insufficient value");
            self.token_value.write(token_id, current - value);
            let slot = self.token_slot.read(token_id);
            self.slot_total_minted.write(slot, self.slot_total_minted.read(slot) - value);
            self.emit(Event::TransferValue(TransferValue {
                from_token_id: token_id, to_token_id: 0_u256, value,
            }));
            self.emit(Event::ForcedValueBurn(ForcedValueBurn { token_id, value, legal_basis }));
        }

        fn pause(ref self: ContractState) {
            self.only_registry();
            assert!(!self.global_paused.read(), "EwpgERC3525: already paused");
            self.global_paused.write(true);
            self.emit(Event::GlobalPaused(GlobalPaused { by: get_caller_address() }));
        }

        fn unpause(ref self: ContractState) {
            self.only_registry();
            assert!(self.global_paused.read(), "EwpgERC3525: not paused");
            self.global_paused.write(false);
            self.emit(Event::GlobalUnpaused(GlobalUnpaused { by: get_caller_address() }));
        }

        fn is_paused(self: @ContractState) -> bool {
            self.global_paused.read()
        }

        fn asset_id(self: @ContractState) -> u256 {
            self.asset_id.read()
        }

        // ── Registry handover (two-step, mirrors Solidity EwpgCompliance) ─────

        fn registry(self: @ContractState) -> ContractAddress {
            self.registry_admin.read()
        }

        fn pending_registry(self: @ContractState) -> ContractAddress {
            self.pending_registry.read()
        }

        fn transfer_registry(ref self: ContractState, new_registry: ContractAddress) {
            self.only_registry();
            assert!(!new_registry.is_zero(), "EwpgERC3525: zero registry address");
            self.pending_registry.write(new_registry);
            self.emit(Event::RegistryTransferStarted(RegistryTransferStarted {
                current_registry: self.registry_admin.read(), pending_registry: new_registry,
            }));
        }

        fn cancel_registry_transfer(ref self: ContractState) {
            self.only_registry();
            self.pending_registry.write(Zero::zero());
            self.emit(Event::RegistryTransferStarted(RegistryTransferStarted {
                current_registry: self.registry_admin.read(), pending_registry: Zero::zero(),
            }));
        }

        fn accept_registry(ref self: ContractState) {
            let pending = self.pending_registry.read();
            assert!(
                !pending.is_zero() && get_caller_address() == pending,
                "EwpgERC3525: caller is not pending registry",
            );
            self.emit(Event::RegistryTransferred(RegistryTransferred {
                previous_registry: self.registry_admin.read(), new_registry: pending,
            }));
            self.registry_admin.write(pending);
            self.pending_registry.write(Zero::zero());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    #[generate_trait]
    impl InternalImpl of InternalTrait {
        fn only_registry(self: @ContractState) {
            assert!(
                get_caller_address() == self.registry_admin.read(),
                "EwpgERC3525: caller is not registry",
            );
        }

        /// Compliance gate for normal (non-forced) movements touching `token_id`.
        fn require_token_transferable(self: @ContractState, token_id: u256) {
            assert!(!self.global_paused.read(), "EwpgERC3525: transfers are paused");
            assert!(
                !self.slot_paused.read(self.token_slot.read(token_id)),
                "EwpgERC3525: slot is paused",
            );
            assert!(!self.token_frozen.read(token_id), "EwpgERC3525: token is frozen");
        }

        /// Compliance-checked value movement between two existing same-slot tokens.
        fn compliant_value_move(
            ref self: ContractState, from_token_id: u256, to_token_id: u256, value: u256,
        ) {
            assert!(
                !self.token_owner.read(to_token_id).is_zero(),
                "EwpgERC3525: invalid token id",
            );
            assert!(
                self.token_slot.read(from_token_id) == self.token_slot.read(to_token_id),
                "EwpgERC3525: tokens in different slots",
            );
            self.require_token_transferable(from_token_id);
            assert!(!self.token_frozen.read(to_token_id), "EwpgERC3525: token is frozen");

            let from_balance = self.token_value.read(from_token_id);
            assert!(from_balance >= value, "EwpgERC3525: insufficient value");
            self.token_value.write(from_token_id, from_balance - value);
            self.token_value.write(to_token_id, self.token_value.read(to_token_id) + value);
            self.emit(Event::TransferValue(TransferValue { from_token_id, to_token_id, value }));
        }

        /// Mints a fresh zero-value token in `slot` owned by `to`.
        fn create_token(ref self: ContractState, to: ContractAddress, slot: u256) -> u256 {
            let token_id = self.next_token_id.read();
            self.next_token_id.write(token_id + 1_u256);
            self.token_slot.write(token_id, slot);
            self.token_owner.write(token_id, to);
            self.balance.write(to, self.balance.read(to) + 1_u256);
            self.emit(Event::Transfer(Transfer { from: Zero::zero(), to, token_id }));
            token_id
        }
    }
}
