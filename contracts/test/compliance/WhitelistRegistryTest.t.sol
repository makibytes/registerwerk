// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/compliance/WhitelistRegistry.sol";

contract WhitelistRegistryTest is Test {
    WhitelistRegistry registry;
    address owner = address(0x1);
    address token1 = address(0x10);
    address token2 = address(0x11);
    address alice = address(0x2);
    address bob = address(0x3);

    function setUp() public {
        vm.prank(owner);
        registry = new WhitelistRegistry();
    }

    // -------------------------------------------------------------------------
    // Deployment
    // -------------------------------------------------------------------------

    function test_ownerIsSetOnDeployment() public view {
        assertEq(registry.owner(), owner);
    }

    // -------------------------------------------------------------------------
    // Whitelisting
    // -------------------------------------------------------------------------

    function test_whitelist_setsAccountForToken() public {
        vm.prank(owner);
        registry.whitelist(token1, alice);
        assertTrue(registry.isWhitelisted(token1, alice));
    }

    function test_whitelist_isTokenScoped() public {
        vm.prank(owner);
        registry.whitelist(token1, alice);
        // alice whitelisted for token1 but not token2
        assertTrue(registry.isWhitelisted(token1, alice));
        assertFalse(registry.isWhitelisted(token2, alice));
    }

    function test_whitelist_canWhitelistMultipleAccountsPerToken() public {
        vm.startPrank(owner);
        registry.whitelist(token1, alice);
        registry.whitelist(token1, bob);
        vm.stopPrank();
        assertTrue(registry.isWhitelisted(token1, alice));
        assertTrue(registry.isWhitelisted(token1, bob));
    }

    function test_whitelist_emitsEvent() public {
        vm.prank(owner);
        vm.expectEmit(true, true, false, false);
        emit IWhitelistRegistry.Whitelisted(token1, alice);
        registry.whitelist(token1, alice);
    }

    function test_whitelist_revertsForZeroTokenAddress() public {
        vm.prank(owner);
        vm.expectRevert("WhitelistRegistry: zero token address");
        registry.whitelist(address(0), alice);
    }

    function test_whitelist_revertsForZeroAccountAddress() public {
        vm.prank(owner);
        vm.expectRevert("WhitelistRegistry: zero account address");
        registry.whitelist(token1, address(0));
    }

    function test_whitelist_revertsIfCalledByNonOwner() public {
        vm.prank(alice);
        vm.expectRevert();
        registry.whitelist(token1, alice);
    }

    // -------------------------------------------------------------------------
    // Remove from whitelist
    // -------------------------------------------------------------------------

    function test_removeFromWhitelist_clearsAccount() public {
        vm.startPrank(owner);
        registry.whitelist(token1, alice);
        assertTrue(registry.isWhitelisted(token1, alice));
        registry.removeFromWhitelist(token1, alice);
        assertFalse(registry.isWhitelisted(token1, alice));
        vm.stopPrank();
    }

    function test_removeFromWhitelist_emitsEvent() public {
        vm.startPrank(owner);
        registry.whitelist(token1, alice);
        vm.expectEmit(true, true, false, false);
        emit IWhitelistRegistry.RemovedFromWhitelist(token1, alice);
        registry.removeFromWhitelist(token1, alice);
        vm.stopPrank();
    }

    function test_removeFromWhitelist_isTokenScoped() public {
        vm.startPrank(owner);
        registry.whitelist(token1, alice);
        registry.whitelist(token2, alice);
        registry.removeFromWhitelist(token1, alice);
        vm.stopPrank();
        // Removed from token1 only; token2 unaffected.
        assertFalse(registry.isWhitelisted(token1, alice));
        assertTrue(registry.isWhitelisted(token2, alice));
    }

    function test_removeFromWhitelist_revertsIfCalledByNonOwner() public {
        vm.prank(owner);
        registry.whitelist(token1, alice);

        vm.prank(alice);
        vm.expectRevert();
        registry.removeFromWhitelist(token1, alice);
    }

    function test_removeFromWhitelist_idempotentForNonWhitelistedAccount() public {
        // Removing an account that was never whitelisted should not revert.
        vm.prank(owner);
        registry.removeFromWhitelist(token1, bob);
        assertFalse(registry.isWhitelisted(token1, bob));
    }

    // -------------------------------------------------------------------------
    // isWhitelisted
    // -------------------------------------------------------------------------

    function test_isWhitelisted_returnsFalseByDefault() public view {
        assertFalse(registry.isWhitelisted(token1, alice));
    }

    function test_isWhitelisted_returnsTrueAfterWhitelisting() public {
        vm.prank(owner);
        registry.whitelist(token1, alice);
        assertTrue(registry.isWhitelisted(token1, alice));
    }

    function test_isWhitelisted_returnsFalseAfterRemoval() public {
        vm.startPrank(owner);
        registry.whitelist(token1, alice);
        registry.removeFromWhitelist(token1, alice);
        vm.stopPrank();
        assertFalse(registry.isWhitelisted(token1, alice));
    }
}
