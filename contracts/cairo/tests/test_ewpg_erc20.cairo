// EwpgERC20 — compliance and registry-handover tests (snforge).

use starknet::ContractAddress;
use snforge_std::{
    declare, ContractClassTrait, DeclareResultTrait, start_cheat_caller_address,
    stop_cheat_caller_address,
};
use registerwerk_cairo::erc20::interface::{
    IEwpgERC20Dispatcher, IEwpgERC20DispatcherTrait, IEwpgERC20AdminDispatcher,
    IEwpgERC20AdminDispatcherTrait,
};

fn registry() -> ContractAddress {
    0x111.try_into().unwrap()
}

fn investor_a() -> ContractAddress {
    0xa1.try_into().unwrap()
}

fn investor_b() -> ContractAddress {
    0xb2.try_into().unwrap()
}

fn stranger() -> ContractAddress {
    0x99.try_into().unwrap()
}

fn deploy_token() -> (IEwpgERC20Dispatcher, IEwpgERC20AdminDispatcher, ContractAddress) {
    let contract = declare("EwpgERC20").unwrap().contract_class();
    let calldata = array!['Test Bond', 'TBND', registry().into()];
    let (address, _) = contract.deploy(@calldata).unwrap();
    (
        IEwpgERC20Dispatcher { contract_address: address },
        IEwpgERC20AdminDispatcher { contract_address: address },
        address,
    )
}

/// Whitelists `account` and mints `amount` to it, acting as the registry.
fn whitelist_and_mint(
    admin: IEwpgERC20AdminDispatcher, address: ContractAddress, account: ContractAddress, amount: u256,
) {
    start_cheat_caller_address(address, registry());
    admin.whitelist(account);
    admin.mint(account, amount);
    stop_cheat_caller_address(address);
}

// ── Issuance ─────────────────────────────────────────────────────────────────

#[test]
fn test_mint_to_whitelisted_account() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    assert!(token.balance_of(investor_a()) == 1000_u256, "balance mismatch");
    assert!(token.total_supply() == 1000_u256, "supply mismatch");
}

#[test]
#[should_panic(expected: "EwpgERC20: recipient not whitelisted")]
fn test_mint_to_non_whitelisted_reverts() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, registry());
    admin.mint(investor_a(), 1000_u256);
}

#[test]
#[should_panic(expected: "EwpgERC20: caller is not registry")]
fn test_mint_by_stranger_reverts() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, stranger());
    admin.mint(investor_a(), 1000_u256);
}

#[test]
#[should_panic(expected: "EwpgERC20: supply cap exceeded")]
fn test_supply_cap_enforced() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, registry());
    admin.set_supply_cap(500_u256);
    admin.whitelist(investor_a());
    admin.mint(investor_a(), 501_u256);
}

// ── Transfers and compliance ─────────────────────────────────────────────────

#[test]
fn test_transfer_between_whitelisted_accounts() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.whitelist(investor_b());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer(investor_b(), 400_u256);
    stop_cheat_caller_address(address);

    assert!(token.balance_of(investor_a()) == 600_u256, "sender balance");
    assert!(token.balance_of(investor_b()) == 400_u256, "recipient balance");
}

#[test]
#[should_panic(expected: "EwpgERC20: recipient not whitelisted")]
fn test_transfer_to_non_whitelisted_reverts() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, investor_a());
    token.transfer(investor_b(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC20: transfers are paused")]
fn test_pause_blocks_transfer() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.whitelist(investor_b());
    admin.pause();
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer(investor_b(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC20: sender is frozen")]
fn test_frozen_sender_blocked() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.whitelist(investor_b());
    admin.freeze_address(investor_a(), 'AWG17');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer(investor_b(), 1_u256);
}

#[test]
fn test_transfer_from_spends_allowance() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.whitelist(investor_b());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.approve(stranger(), 300_u256);
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, stranger());
    token.transfer_from(investor_a(), investor_b(), 200_u256);
    stop_cheat_caller_address(address);

    assert!(token.balance_of(investor_b()) == 200_u256, "recipient balance");
    assert!(token.allowance(investor_a(), stranger()) == 100_u256, "remaining allowance");
}

// ── Forced regulatory operations ─────────────────────────────────────────────

#[test]
fn test_forced_transfer_bypasses_freeze_and_whitelist() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);

    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_a(), 'AWG17');
    // investor_b is NOT whitelisted — a §24 Berichtigung must still execute.
    admin.forced_transfer(investor_a(), investor_b(), 1000_u256, 'BaFin-Az-2026-001');
    stop_cheat_caller_address(address);

    assert!(token.balance_of(investor_b()) == 1000_u256, "forced transfer balance");
}

#[test]
fn test_force_burn_reduces_supply() {
    let (token, admin, address) = deploy_token();
    whitelist_and_mint(admin, address, investor_a(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.pause(); // §26 Einziehung must work even while paused
    admin.force_burn(investor_a(), 400_u256, 'Einziehung-2026-002');
    stop_cheat_caller_address(address);

    assert!(token.balance_of(investor_a()) == 600_u256, "post-burn balance");
    assert!(token.total_supply() == 600_u256, "post-burn supply");
}

// ── Registry handover ─────────────────────────────────────────────────────────

#[test]
fn test_registry_handover_two_step() {
    let (_, admin, address) = deploy_token();
    let new_registry: ContractAddress = 0x222.try_into().unwrap();

    start_cheat_caller_address(address, registry());
    admin.transfer_registry(new_registry);
    stop_cheat_caller_address(address);
    assert!(admin.registry() == registry(), "authority moves only on accept");

    start_cheat_caller_address(address, new_registry);
    admin.accept_registry();
    admin.whitelist(investor_a()); // new registry has full power
    stop_cheat_caller_address(address);

    assert!(admin.registry() == new_registry, "registry updated");
    assert!(admin.pending_registry().into() == 0, "pending cleared");
}

#[test]
#[should_panic(expected: "EwpgERC20: caller is not registry")]
fn test_old_registry_loses_power_after_handover() {
    let (_, admin, address) = deploy_token();
    let new_registry: ContractAddress = 0x222.try_into().unwrap();
    start_cheat_caller_address(address, registry());
    admin.transfer_registry(new_registry);
    stop_cheat_caller_address(address);
    start_cheat_caller_address(address, new_registry);
    admin.accept_registry();
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, registry());
    admin.pause();
}

#[test]
#[should_panic(expected: "EwpgERC20: caller is not pending registry")]
fn test_stranger_cannot_accept_handover() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, registry());
    admin.transfer_registry(0x222.try_into().unwrap());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, stranger());
    admin.accept_registry();
}
