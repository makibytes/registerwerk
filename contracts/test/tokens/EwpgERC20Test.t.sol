// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/tokens/EwpgERC20.sol";

contract EwpgERC20Test is Test {
    EwpgERC20 token;
    address registry = address(0x1);
    address alice = address(0x2);
    address bob = address(0x3);
    bytes32 assetId = keccak256("asset-1");

    function setUp() public {
        vm.prank(registry);
        token = new EwpgERC20("Test Security Token", "TST", registry, assetId);
    }

    // -------------------------------------------------------------------------
    // Deployment / metadata
    // -------------------------------------------------------------------------

    function test_metadata() public view {
        assertEq(token.name(), "Test Security Token");
        assertEq(token.symbol(), "TST");
        assertEq(token.assetId(), assetId);
        assertEq(token.registry(), registry);
    }

    // -------------------------------------------------------------------------
    // Minting
    // -------------------------------------------------------------------------

    function test_mint_revertsIfNotWhitelisted() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC20: recipient not whitelisted");
        token.mint(alice, 1000e18);
    }

    function test_mint_succeedsForWhitelistedAddress() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 1000e18);
        vm.stopPrank();
        assertEq(token.balanceOf(alice), 1000e18);
    }

    function test_onlyRegistry_canMint() public {
        vm.prank(registry);
        token.whitelist(alice);

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.mint(alice, 1000e18);
    }

    // -------------------------------------------------------------------------
    // Burning
    // -------------------------------------------------------------------------

    function test_burn_succeeds() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 1000e18);
        token.burn(alice, 500e18);
        vm.stopPrank();
        assertEq(token.balanceOf(alice), 500e18);
    }

    function test_burn_revertsIfCalledByNonRegistry() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 1000e18);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.burn(alice, 100e18);
    }

    function test_burn_revertsIfInsufficientBalance() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 100e18);
        vm.expectRevert();
        token.burn(alice, 200e18);
        vm.stopPrank();
    }

    // -------------------------------------------------------------------------
    // Transfers
    // -------------------------------------------------------------------------

    function test_transfer_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 1000e18);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC20: recipient not whitelisted");
        token.transfer(bob, 100e18);
    }

    function test_transfer_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, 1000e18);
        vm.stopPrank();

        vm.prank(alice);
        token.transfer(bob, 100e18);
        assertEq(token.balanceOf(bob), 100e18);
        assertEq(token.balanceOf(alice), 900e18);
    }

    function test_transferFrom_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, 1000e18);
        vm.stopPrank();

        vm.prank(alice);
        token.approve(bob, 500e18);

        vm.prank(bob);
        vm.expectRevert("EwpgERC20: recipient not whitelisted");
        token.transferFrom(alice, bob, 100e18);
    }

    function test_transferFrom_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, 1000e18);
        vm.stopPrank();

        vm.prank(alice);
        token.approve(bob, 500e18);

        vm.prank(bob);
        token.transferFrom(alice, bob, 200e18);
        assertEq(token.balanceOf(bob), 200e18);
    }

    // -------------------------------------------------------------------------
    // Whitelist management
    // -------------------------------------------------------------------------

    function test_whitelist_emitsEvent() public {
        vm.prank(registry);
        vm.expectEmit(true, false, false, false);
        emit IEwpgCompliant.Whitelisted(alice);
        token.whitelist(alice);
    }

    function test_removeFromWhitelist_preventsTransfer() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, 1000e18);
        token.removeFromWhitelist(bob);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC20: recipient not whitelisted");
        token.transfer(bob, 100e18);
    }

    function test_whitelist_revertsForZeroAddress() public {
        vm.prank(registry);
        vm.expectRevert("EwpgCompliance: zero address");
        token.whitelist(address(0));
    }

    function test_whitelist_revertsIfCalledByNonRegistry() public {
        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.whitelist(alice);
    }

    function test_removeFromWhitelist_revertsIfCalledByNonRegistry() public {
        vm.prank(registry);
        token.whitelist(alice);

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.removeFromWhitelist(alice);
    }
}
