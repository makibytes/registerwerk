// EwpgERC20 — fungible eWpG security token on Starknet.
//
// Mirrors contracts/src/tokens/EwpgERC20.sol (registry-controlled whitelist,
// address freeze, global pause, supply cap, forced regulatory operations, and
// the two-step registry handover). Self-contained: name/symbol are felt252
// short strings so the constructor calldata matches the backend's
// StarknetTokenService (name, symbol, registry — three felts).
//
// Admin powers (all only_registry):
//   - mint / burn            : issuance / cancellation
//   - pause / unpause        : MiCAR Art. 36, 84 — suspend all transfers
//   - freeze_address         : AWG §17, GwG §40 — block sanctioned/AML wallets
//   - forced_transfer        : eWpG §24 Berichtigung — BaFin/court-ordered transfer
//   - force_burn             : eWpG §26 Einziehung — compulsory cancellation
//   - set_supply_cap         : MiCAR Art. 46 — regulatory issuance ceiling
//   - transfer_registry / accept_registry : key rotation without losing control

use starknet::ContractAddress;

#[derive(Drop, starknet::Event)]
pub struct Transfer {
    #[key]
    pub from: ContractAddress,
    #[key]
    pub to: ContractAddress,
    pub value: u256,
}

#[derive(Drop, starknet::Event)]
pub struct Approval {
    #[key]
    pub owner: ContractAddress,
    #[key]
    pub spender: ContractAddress,
    pub value: u256,
}

#[derive(Drop, starknet::Event)]
pub struct Whitelisted {
    #[key]
    pub account: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct RemovedFromWhitelist {
    #[key]
    pub account: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct Paused {
    pub by: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct Unpaused {
    pub by: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct AddressFrozen {
    #[key]
    pub account: ContractAddress,
    pub reason: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct AddressUnfrozen {
    #[key]
    pub account: ContractAddress,
}

#[derive(Drop, starknet::Event)]
pub struct SupplyCapUpdated {
    pub old_cap: u256,
    pub new_cap: u256,
}

#[derive(Drop, starknet::Event)]
pub struct ForcedTransfer {
    #[key]
    pub from: ContractAddress,
    #[key]
    pub to: ContractAddress,
    pub value: u256,
    pub legal_basis: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct ForcedApprove {
    #[key]
    pub owner: ContractAddress,
    #[key]
    pub spender: ContractAddress,
    pub value: u256,
    pub legal_basis: felt252,
}

#[derive(Drop, starknet::Event)]
pub struct ForceBurned {
    #[key]
    pub from: ContractAddress,
    pub value: u256,
    pub legal_basis: felt252,
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
pub mod EwpgERC20 {
    use core::num::traits::Zero;
    use starknet::{ContractAddress, get_caller_address};
    use starknet::storage::{
        Map, StoragePointerReadAccess, StoragePointerWriteAccess, StorageMapReadAccess,
        StorageMapWriteAccess,
    };
    use registerwerk_cairo::erc20::interface::{IEwpgERC20, IEwpgERC20Admin};
    use super::{
        Transfer, Approval, Whitelisted, RemovedFromWhitelist, Paused, Unpaused, AddressFrozen,
        AddressUnfrozen, SupplyCapUpdated, ForcedTransfer, ForcedApprove, ForceBurned,
        RegistryTransferStarted, RegistryTransferred,
    };

    #[storage]
    struct Storage {
        name: felt252,
        symbol: felt252,
        total_supply: u256,
        balances: Map<ContractAddress, u256>,
        allowances: Map<(ContractAddress, ContractAddress), u256>,
        // Registry authority + two-step handover
        registry: ContractAddress,
        pending_registry: ContractAddress,
        // Compliance state
        whitelisted: Map<ContractAddress, bool>,
        frozen: Map<ContractAddress, bool>,
        paused: bool,
        supply_cap: u256, // 0 = unlimited
    }

    #[event]
    #[derive(Drop, starknet::Event)]
    enum Event {
        Transfer: Transfer,
        Approval: Approval,
        Whitelisted: Whitelisted,
        RemovedFromWhitelist: RemovedFromWhitelist,
        Paused: Paused,
        Unpaused: Unpaused,
        AddressFrozen: AddressFrozen,
        AddressUnfrozen: AddressUnfrozen,
        SupplyCapUpdated: SupplyCapUpdated,
        ForcedTransfer: ForcedTransfer,
        ForcedApprove: ForcedApprove,
        ForceBurned: ForceBurned,
        RegistryTransferStarted: RegistryTransferStarted,
        RegistryTransferred: RegistryTransferred,
    }

    #[constructor]
    fn constructor(
        ref self: ContractState, name: felt252, symbol: felt252, registry: ContractAddress,
    ) {
        assert!(!registry.is_zero(), "EwpgERC20: zero registry address");
        self.name.write(name);
        self.symbol.write(symbol);
        self.registry.write(registry);
    }

    // ── ERC-20 standard surface ────────────────────────────────────────────────

    #[abi(embed_v0)]
    impl EwpgERC20Impl of IEwpgERC20<ContractState> {
        fn name(self: @ContractState) -> felt252 {
            self.name.read()
        }

        fn symbol(self: @ContractState) -> felt252 {
            self.symbol.read()
        }

        fn decimals(self: @ContractState) -> u8 {
            18
        }

        fn total_supply(self: @ContractState) -> u256 {
            self.total_supply.read()
        }

        fn balance_of(self: @ContractState, account: ContractAddress) -> u256 {
            self.balances.read(account)
        }

        fn allowance(
            self: @ContractState, owner: ContractAddress, spender: ContractAddress,
        ) -> u256 {
            self.allowances.read((owner, spender))
        }

        fn transfer(ref self: ContractState, to: ContractAddress, amount: u256) -> bool {
            let from = get_caller_address();
            self.compliant_transfer(from, to, amount);
            true
        }

        fn transfer_from(
            ref self: ContractState, from: ContractAddress, to: ContractAddress, amount: u256,
        ) -> bool {
            self.spend_allowance(from, get_caller_address(), amount);
            self.compliant_transfer(from, to, amount);
            true
        }

        fn approve(ref self: ContractState, spender: ContractAddress, amount: u256) -> bool {
            let owner = get_caller_address();
            self.allowances.write((owner, spender), amount);
            self.emit(Event::Approval(Approval { owner, spender, value: amount }));
            true
        }
    }

    // ── Registry admin surface ─────────────────────────────────────────────────

    #[abi(embed_v0)]
    impl EwpgERC20AdminImpl of IEwpgERC20Admin<ContractState> {
        fn mint(ref self: ContractState, to: ContractAddress, amount: u256) {
            self.only_registry();
            assert!(!to.is_zero(), "EwpgERC20: mint to zero address");
            assert!(!self.paused.read(), "EwpgERC20: transfers are paused");
            assert!(!self.frozen.read(to), "EwpgERC20: recipient is frozen");
            assert!(self.whitelisted.read(to), "EwpgERC20: recipient not whitelisted");

            let cap = self.supply_cap.read();
            let supply = self.total_supply.read();
            if cap > 0_u256 {
                assert!(supply + amount <= cap, "EwpgERC20: supply cap exceeded");
            }
            self.total_supply.write(supply + amount);
            self.balances.write(to, self.balances.read(to) + amount);
            self.emit(Event::Transfer(Transfer { from: Zero::zero(), to, value: amount }));
        }

        fn burn(ref self: ContractState, from: ContractAddress, amount: u256) {
            self.only_registry();
            assert!(!self.paused.read(), "EwpgERC20: transfers are paused");
            assert!(!self.frozen.read(from), "EwpgERC20: sender is frozen");
            self.debit(from, amount);
            self.total_supply.write(self.total_supply.read() - amount);
            self.emit(Event::Transfer(Transfer { from, to: Zero::zero(), value: amount }));
        }

        fn whitelist(ref self: ContractState, account: ContractAddress) {
            self.only_registry();
            assert!(!account.is_zero(), "EwpgERC20: zero address");
            self.whitelisted.write(account, true);
            self.emit(Event::Whitelisted(Whitelisted { account }));
        }

        fn remove_from_whitelist(ref self: ContractState, account: ContractAddress) {
            self.only_registry();
            self.whitelisted.write(account, false);
            self.emit(Event::RemovedFromWhitelist(RemovedFromWhitelist { account }));
        }

        fn is_whitelisted(self: @ContractState, account: ContractAddress) -> bool {
            self.whitelisted.read(account)
        }

        fn pause(ref self: ContractState) {
            self.only_registry();
            assert!(!self.paused.read(), "EwpgERC20: already paused");
            self.paused.write(true);
            self.emit(Event::Paused(Paused { by: get_caller_address() }));
        }

        fn unpause(ref self: ContractState) {
            self.only_registry();
            assert!(self.paused.read(), "EwpgERC20: not paused");
            self.paused.write(false);
            self.emit(Event::Unpaused(Unpaused { by: get_caller_address() }));
        }

        fn is_paused(self: @ContractState) -> bool {
            self.paused.read()
        }

        fn freeze_address(ref self: ContractState, account: ContractAddress, reason: felt252) {
            self.only_registry();
            assert!(!account.is_zero(), "EwpgERC20: zero address");
            self.frozen.write(account, true);
            self.emit(Event::AddressFrozen(AddressFrozen { account, reason }));
        }

        fn unfreeze_address(ref self: ContractState, account: ContractAddress) {
            self.only_registry();
            self.frozen.write(account, false);
            self.emit(Event::AddressUnfrozen(AddressUnfrozen { account }));
        }

        fn is_frozen(self: @ContractState, account: ContractAddress) -> bool {
            self.frozen.read(account)
        }

        fn set_supply_cap(ref self: ContractState, new_cap: u256) {
            self.only_registry();
            let old_cap = self.supply_cap.read();
            self.supply_cap.write(new_cap);
            self.emit(Event::SupplyCapUpdated(SupplyCapUpdated { old_cap, new_cap }));
        }

        fn supply_cap(self: @ContractState) -> u256 {
            self.supply_cap.read()
        }

        // Forced transfer — eWpG §24 Berichtigung. Bypasses whitelist, freeze, pause.
        fn forced_transfer(
            ref self: ContractState,
            from: ContractAddress,
            to: ContractAddress,
            amount: u256,
            legal_basis: felt252,
        ) {
            self.only_registry();
            assert!(!to.is_zero(), "EwpgERC20: transfer to zero address");
            self.debit(from, amount);
            self.balances.write(to, self.balances.read(to) + amount);
            self.emit(Event::Transfer(Transfer { from, to, value: amount }));
            self.emit(Event::ForcedTransfer(ForcedTransfer { from, to, value: amount, legal_basis }));
        }

        // Forced approve — regulatory allowance override. The eventual transfer_from
        // still passes the compliance checks (whitelist/freeze/pause), matching Solidity.
        fn forced_approve(
            ref self: ContractState,
            owner: ContractAddress,
            spender: ContractAddress,
            amount: u256,
            legal_basis: felt252,
        ) {
            self.only_registry();
            self.allowances.write((owner, spender), amount);
            self.emit(Event::Approval(Approval { owner, spender, value: amount }));
            self.emit(Event::ForcedApprove(ForcedApprove { owner, spender, value: amount, legal_basis }));
        }

        // Force burn — eWpG §26 Einziehung. Bypasses freeze and pause.
        fn force_burn(
            ref self: ContractState, from: ContractAddress, amount: u256, legal_basis: felt252,
        ) {
            self.only_registry();
            self.debit(from, amount);
            self.total_supply.write(self.total_supply.read() - amount);
            self.emit(Event::Transfer(Transfer { from, to: Zero::zero(), value: amount }));
            self.emit(Event::ForceBurned(ForceBurned { from, value: amount, legal_basis }));
        }

        // ── Registry handover (two-step, see Solidity EwpgCompliance) ─────────

        fn registry(self: @ContractState) -> ContractAddress {
            self.registry.read()
        }

        fn pending_registry(self: @ContractState) -> ContractAddress {
            self.pending_registry.read()
        }

        fn transfer_registry(ref self: ContractState, new_registry: ContractAddress) {
            self.only_registry();
            assert!(!new_registry.is_zero(), "EwpgERC20: zero registry address");
            self.pending_registry.write(new_registry);
            self.emit(Event::RegistryTransferStarted(RegistryTransferStarted {
                current_registry: self.registry.read(), pending_registry: new_registry,
            }));
        }

        fn cancel_registry_transfer(ref self: ContractState) {
            self.only_registry();
            self.pending_registry.write(Zero::zero());
            self.emit(Event::RegistryTransferStarted(RegistryTransferStarted {
                current_registry: self.registry.read(), pending_registry: Zero::zero(),
            }));
        }

        fn accept_registry(ref self: ContractState) {
            let pending = self.pending_registry.read();
            assert!(
                !pending.is_zero() && get_caller_address() == pending,
                "EwpgERC20: caller is not pending registry",
            );
            self.emit(Event::RegistryTransferred(RegistryTransferred {
                previous_registry: self.registry.read(), new_registry: pending,
            }));
            self.registry.write(pending);
            self.pending_registry.write(Zero::zero());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    #[generate_trait]
    impl InternalImpl of InternalTrait {
        fn only_registry(self: @ContractState) {
            assert!(
                get_caller_address() == self.registry.read(),
                "EwpgERC20: caller is not registry",
            );
        }

        /// Compliance-checked balance movement for all normal (non-forced) paths.
        fn compliant_transfer(
            ref self: ContractState, from: ContractAddress, to: ContractAddress, amount: u256,
        ) {
            assert!(!to.is_zero(), "EwpgERC20: transfer to zero address");
            assert!(!self.paused.read(), "EwpgERC20: transfers are paused");
            assert!(!self.frozen.read(from), "EwpgERC20: sender is frozen");
            assert!(!self.frozen.read(to), "EwpgERC20: recipient is frozen");
            assert!(self.whitelisted.read(to), "EwpgERC20: recipient not whitelisted");
            self.debit(from, amount);
            self.balances.write(to, self.balances.read(to) + amount);
            self.emit(Event::Transfer(Transfer { from, to, value: amount }));
        }

        fn debit(ref self: ContractState, from: ContractAddress, amount: u256) {
            let balance = self.balances.read(from);
            assert!(balance >= amount, "EwpgERC20: insufficient balance");
            self.balances.write(from, balance - amount);
        }

        fn spend_allowance(
            ref self: ContractState, owner: ContractAddress, spender: ContractAddress, amount: u256,
        ) {
            let current = self.allowances.read((owner, spender));
            assert!(current >= amount, "EwpgERC20: insufficient allowance");
            self.allowances.write((owner, spender), current - amount);
        }
    }
}
