// EwpgERC3525 — slot/value semantics, compliance, and handover tests (snforge).

use starknet::ContractAddress;
use snforge_std::{
    declare, ContractClassTrait, DeclareResultTrait, start_cheat_caller_address,
    stop_cheat_caller_address,
};
use registerwerk_cairo::erc3525::interface::{
    IERC3525Dispatcher, IERC3525DispatcherTrait, IEwpgERC3525AdminDispatcher,
    IEwpgERC3525AdminDispatcherTrait,
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

/// Never whitelisted by `deploy_token`.
fn investor_c() -> ContractAddress {
    0xc3.try_into().unwrap()
}

fn slot_2026() -> u256 {
    2026_u256
}

fn slot_2030() -> u256 {
    2030_u256
}

fn deploy_token() -> (IERC3525Dispatcher, IEwpgERC3525AdminDispatcher, ContractAddress) {
    let contract = declare("EwpgERC3525").unwrap().contract_class();
    // (name, symbol, value_decimals, registry, asset_id.low, asset_id.high)
    let calldata = array!['RW Bond 2026', 'RWB26', 18, registry().into(), 0x42, 0];
    let (address, _) = contract.deploy(@calldata).unwrap();
    let admin = IEwpgERC3525AdminDispatcher { contract_address: address };
    // KYC-onboard the two standard investors; investor_c() stays off the whitelist.
    start_cheat_caller_address(address, registry());
    admin.whitelist(investor_a());
    admin.whitelist(investor_b());
    stop_cheat_caller_address(address);
    (IERC3525Dispatcher { contract_address: address }, admin, address)
}

/// Mints a token with `value` in `slot` to `to`, acting as the registry.
fn mint_as_registry(
    admin: IEwpgERC3525AdminDispatcher,
    address: ContractAddress,
    to: ContractAddress,
    slot: u256,
    value: u256,
) -> u256 {
    start_cheat_caller_address(address, registry());
    let token_id = admin.mint(to, slot, value);
    stop_cheat_caller_address(address);
    token_id
}

// ── Constructor and views ─────────────────────────────────────────────────────

#[test]
fn test_constructor_views() {
    let (token, admin, _) = deploy_token();
    assert!(token.name() == 'RW Bond 2026', "name");
    assert!(token.symbol() == 'RWB26', "symbol");
    assert!(token.value_decimals() == 18, "decimals");
    assert!(admin.asset_id() == 0x42_u256, "asset id");
    assert!(admin.registry() == registry(), "registry");
}

// ── Mint / slot accounting ────────────────────────────────────────────────────

#[test]
fn test_mint_tracks_slot_value_and_owner() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    assert!(token.owner_of(token_id) == investor_a(), "owner");
    assert!(token.slot_of(token_id) == slot_2026(), "slot");
    assert!(token.balance_of_token(token_id) == 1000_u256, "value");
    assert!(token.balance_of(investor_a()) == 1_u256, "erc721 balance");
    assert!(admin.slot_total_minted(slot_2026()) == 1000_u256, "slot minted");
}

#[test]
#[should_panic(expected: "EwpgERC3525: slot supply cap exceeded")]
fn test_slot_supply_cap_enforced() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, registry());
    admin.set_slot_supply_cap(slot_2026(), 500_u256);
    admin.mint(investor_a(), slot_2026(), 501_u256);
}

// ── Value transfers ───────────────────────────────────────────────────────────

#[test]
fn test_owner_transfers_value_between_same_slot_tokens() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 400_u256);
    stop_cheat_caller_address(address);

    assert!(token.balance_of_token(from_id) == 600_u256, "source value");
    assert!(token.balance_of_token(to_id) == 400_u256, "target value");
}

#[test]
#[should_panic(expected: "EwpgERC3525: tokens in different slots")]
fn test_cross_slot_value_transfer_reverts() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2030(), 0_u256);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
fn test_value_allowance_spent_by_operator() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);

    start_cheat_caller_address(address, investor_a());
    token.approve_value(from_id, investor_b(), 300_u256);
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_b());
    token.transfer_value_from(from_id, to_id, 200_u256);
    stop_cheat_caller_address(address);

    assert!(token.allowance(from_id, investor_b()) == 100_u256, "remaining allowance");
    assert!(token.balance_of_token(to_id) == 200_u256, "target value");
}

#[test]
fn test_transfer_value_to_mints_new_token_in_same_slot() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, investor_a());
    let new_id = token.transfer_value_to(from_id, investor_b(), 250_u256);
    stop_cheat_caller_address(address);

    assert!(token.owner_of(new_id) == investor_b(), "new token owner");
    assert!(token.slot_of(new_id) == slot_2026(), "new token slot");
    assert!(token.balance_of_token(new_id) == 250_u256, "new token value");
    assert!(token.balance_of_token(from_id) == 750_u256, "source value");
}

// ── Compliance enforcement vs. forced operations ─────────────────────────────

#[test]
#[should_panic(expected: "EwpgERC3525: slot is paused")]
fn test_slot_pause_blocks_value_transfer() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, registry());
    admin.pause_slot(slot_2026());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: token is frozen")]
fn test_frozen_token_blocks_value_transfer() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_token(from_id, 'GwG40');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
fn test_forced_value_transfer_bypasses_freeze_and_pause() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);

    start_cheat_caller_address(address, registry());
    admin.freeze_token(from_id, 'GwG40');
    admin.pause();
    admin.forced_transfer_value(from_id, to_id, 1000_u256, 'BaFin-Az-2026-003');
    stop_cheat_caller_address(address);

    assert!(token.balance_of_token(to_id) == 1000_u256, "forced value");
}

#[test]
fn test_force_burn_value_reduces_slot_total() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, registry());
    admin.force_burn_value(token_id, 400_u256, 'Einziehung-2026-004');
    stop_cheat_caller_address(address);

    assert!(token.balance_of_token(token_id) == 600_u256, "post-burn value");
    assert!(admin.slot_total_minted(slot_2026()) == 600_u256, "slot total reduced");
}

// ── Whole-token transfer ──────────────────────────────────────────────────────

#[test]
fn test_owner_transfers_whole_token() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, investor_a());
    token.transfer_from(investor_a(), investor_b(), token_id);
    stop_cheat_caller_address(address);

    assert!(token.owner_of(token_id) == investor_b(), "new owner");
    assert!(token.balance_of(investor_a()) == 0_u256, "old erc721 balance");
    assert!(token.balance_of(investor_b()) == 1_u256, "new erc721 balance");
}

#[test]
#[should_panic(expected: "EwpgERC3525: caller is not the owner")]
fn test_non_owner_cannot_transfer_whole_token() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, investor_b());
    token.transfer_from(investor_a(), investor_b(), token_id);
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
    admin.pause(); // new registry has full power
    stop_cheat_caller_address(address);

    assert!(admin.registry() == new_registry, "registry updated");
    assert!(admin.is_paused(), "new registry can pause");
}

#[test]
#[should_panic(expected: "EwpgERC3525: caller is not pending registry")]
fn test_stranger_cannot_accept_handover() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, registry());
    admin.transfer_registry(0x222.try_into().unwrap());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    admin.accept_registry();
}

// ── Holder whitelist + address freeze (T1-03) ─────────────────────────────────

#[test]
#[should_panic(expected: "EwpgERC3525: recipient not whitelisted")]
fn test_mint_to_non_whitelisted_reverts() {
    let (_, admin, address) = deploy_token();
    mint_as_registry(admin, address, investor_c(), slot_2026(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: recipient not whitelisted")]
fn test_whole_token_transfer_to_non_whitelisted_reverts() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, investor_a());
    token.transfer_from(investor_a(), investor_c(), token_id);
}

#[test]
#[should_panic(expected: "EwpgERC3525: recipient not whitelisted")]
fn test_transfer_value_to_non_whitelisted_reverts() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_to(from_id, investor_c(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: recipient not whitelisted")]
fn test_value_into_dewhitelisted_holder_reverts() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, registry());
    admin.remove_from_whitelist(investor_b());
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: recipient is frozen")]
fn test_frozen_address_cannot_receive_new_token() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_b(), 'GwG40');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_to(from_id, investor_b(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: destination address is frozen")]
fn test_frozen_owner_of_existing_token_cannot_receive_value() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_b(), 'GwG40');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: source address is frozen")]
fn test_frozen_owner_cannot_send_value() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_a(), 'AWG17');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: caller address is frozen")]
fn test_frozen_operator_cannot_spend_allowance() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 0_u256);
    start_cheat_caller_address(address, investor_a());
    token.approve_value(from_id, investor_b(), 500_u256);
    stop_cheat_caller_address(address);
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_b(), 'AWG17');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_b());
    token.transfer_value_from(from_id, to_id, 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: caller is not registry")]
fn test_only_registry_can_whitelist() {
    let (_, admin, address) = deploy_token();
    start_cheat_caller_address(address, investor_a());
    admin.whitelist(investor_c());
}

#[test]
fn test_whitelist_and_freeze_views() {
    let (_, admin, address) = deploy_token();
    assert!(admin.is_whitelisted(investor_a()), "fixture whitelisted");
    assert!(!admin.is_whitelisted(investor_c()), "c not whitelisted");
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_a(), 'GwG40');
    assert!(admin.is_frozen(investor_a()), "frozen");
    admin.unfreeze_address(investor_a());
    admin.remove_from_whitelist(investor_a());
    stop_cheat_caller_address(address);
    assert!(!admin.is_frozen(investor_a()), "unfrozen");
    assert!(!admin.is_whitelisted(investor_a()), "removed");
}

#[test]
fn test_forced_value_transfer_bypasses_address_freeze_and_whitelist() {
    let (token, admin, address) = deploy_token();
    let from_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_b(), slot_2026(), 0_u256);

    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_a(), 'GwG40');
    admin.remove_from_whitelist(investor_b());
    admin.forced_transfer_value(from_id, to_id, 1000_u256, 'BaFin-Az-2026-003');
    stop_cheat_caller_address(address);

    assert!(token.balance_of_token(to_id) == 1000_u256, "forced value");
}

// ── Value allowances do not survive an ownership change (T1-03 / T1-01 parity) ─

#[test]
#[should_panic(expected: "EwpgERC3525: insufficient value allowance")]
fn test_stale_allowance_after_whole_token_transfer_reverts() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let sink_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 0_u256);

    // investor_a grants an operator allowance and then sells the whole token.
    let operator: ContractAddress = 0xd4.try_into().unwrap();
    start_cheat_caller_address(address, investor_a());
    token.approve_value(token_id, operator, 1000_u256);
    token.transfer_from(investor_a(), investor_b(), token_id);
    stop_cheat_caller_address(address);

    assert!(token.allowance(token_id, operator) == 0_u256, "allowance reset on sale");
    // The previous owner's operator tries to drain the new owner's position.
    start_cheat_caller_address(address, operator);
    token.transfer_value_from(token_id, sink_id, 1000_u256);
}

#[test]
fn test_new_owner_can_grant_fresh_allowance_after_transfer() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    let to_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 0_u256);
    let operator: ContractAddress = 0xd4.try_into().unwrap();

    start_cheat_caller_address(address, investor_a());
    token.approve_value(token_id, operator, 1000_u256);
    token.transfer_from(investor_a(), investor_b(), token_id);
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_b());
    token.approve_value(token_id, operator, 300_u256);
    stop_cheat_caller_address(address);
    assert!(token.allowance(token_id, operator) == 300_u256, "fresh allowance");

    start_cheat_caller_address(address, operator);
    token.transfer_value_from(token_id, to_id, 300_u256);
    stop_cheat_caller_address(address);
    assert!(token.balance_of_token(to_id) == 300_u256, "spent fresh allowance");
}

#[test]
#[should_panic(expected: "EwpgERC3525: token is frozen")]
fn test_approve_value_while_token_frozen_reverts() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_token(token_id, 'GwG40');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.approve_value(token_id, investor_b(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: owner address is frozen")]
fn test_approve_value_while_owner_frozen_reverts() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.freeze_address(investor_a(), 'GwG40');
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.approve_value(token_id, investor_b(), 1_u256);
}

#[test]
#[should_panic(expected: "EwpgERC3525: transfers are paused")]
fn test_approve_value_while_paused_reverts() {
    let (token, admin, address) = deploy_token();
    let token_id = mint_as_registry(admin, address, investor_a(), slot_2026(), 1000_u256);
    start_cheat_caller_address(address, registry());
    admin.pause();
    stop_cheat_caller_address(address);

    start_cheat_caller_address(address, investor_a());
    token.approve_value(token_id, investor_b(), 1_u256);
}
