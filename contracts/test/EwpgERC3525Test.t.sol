// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC3525.sol";
import "../src/factory/AssetTokenFactory.sol";

contract EwpgERC3525Test is Test {
    EwpgERC3525 token;
    AssetTokenFactory factory;

    address registry = makeAddr("registry");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");
    address mallory = makeAddr("mallory");

    bytes32 constant ASSET_ID = keccak256("test-asset-uuid");
    uint256 constant SLOT_BONDS = 1;

    function setUp() public {
        vm.startPrank(registry);
        token = new EwpgERC3525("Registerwerk Bond A", "RWBA", registry, ASSET_ID);
        token.whitelist(alice);
        token.whitelist(bob);
        vm.stopPrank();
    }

    // ── Minting ───────────────────────────────────────────────────────────────

    function test_mint_createsTokenWithCorrectSlotAndValue() public {
        vm.prank(registry);
        uint256 tokenId = token.mint(alice, SLOT_BONDS, 1000e18);
        assertEq(token.slotOf(tokenId), SLOT_BONDS);
        assertEq(token.balanceOf(tokenId), 1000e18);
        assertEq(token.ownerOf(tokenId), alice);
    }

    function test_mint_revertsForNonWhitelistedRecipient() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC3525: recipient not whitelisted");
        token.mint(mallory, SLOT_BONDS, 100e18);
    }

    function test_mint_respectsSlotSupplyCap() public {
        vm.startPrank(registry);
        token.setSlotSupplyCap(SLOT_BONDS, 500e18);
        vm.expectRevert("EwpgERC3525: slot supply cap exceeded");
        token.mint(alice, SLOT_BONDS, 501e18);
        vm.stopPrank();
    }

    function test_mint_revertsOnPausedSlot() public {
        vm.startPrank(registry);
        token.pauseSlot(SLOT_BONDS);
        vm.expectRevert("EwpgERC3525: slot is paused");
        token.mint(alice, SLOT_BONDS, 100e18);
        vm.stopPrank();
    }

    // ── Value transfer compliance ──────────────────────────────────────────────

    function test_transferValue_blockedWhenSlotPaused() public {
        vm.startPrank(registry);
        uint256 tokenA = token.mint(alice, SLOT_BONDS, 1000e18);
        uint256 tokenB = token.mint(bob, SLOT_BONDS, 0);
        token.pauseSlot(SLOT_BONDS);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC3525: slot is paused");
        token.transferFrom(tokenA, tokenB, 100e18);
    }

    function test_transferValue_blockedWhenSourceTokenFrozen() public {
        vm.startPrank(registry);
        uint256 tokenA = token.mint(alice, SLOT_BONDS, 1000e18);
        uint256 tokenB = token.mint(bob, SLOT_BONDS, 0);
        token.freezeToken(tokenA, "AML check");
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC3525: source token is frozen");
        token.transferFrom(tokenA, tokenB, 100e18);
    }

    function test_transferValue_allowedAfterUnfreeze() public {
        vm.startPrank(registry);
        uint256 tokenA = token.mint(alice, SLOT_BONDS, 1000e18);
        uint256 tokenB = token.mint(bob, SLOT_BONDS, 0);
        token.freezeToken(tokenA, "AML");
        token.unfreezeToken(tokenA);
        vm.stopPrank();

        vm.prank(alice);
        token.approve(tokenA, alice, 200e18);
        vm.prank(alice);
        token.transferFrom(tokenA, tokenB, 200e18);
        assertEq(token.balanceOf(tokenB), 200e18);
    }

    // ── Forced operations ─────────────────────────────────────────────────────

    function test_forcedTransferValue_bypassesCompliance() public {
        vm.startPrank(registry);
        uint256 tokenA = token.mint(alice, SLOT_BONDS, 1000e18);
        uint256 tokenB = token.mint(bob, SLOT_BONDS, 0);
        token.pauseSlot(SLOT_BONDS);
        token.forcedTransferValue(tokenA, tokenB, 500e18, unicode"BaFin §24");
        vm.stopPrank();
        assertEq(token.balanceOf(tokenA), 500e18);
        assertEq(token.balanceOf(tokenB), 500e18);
    }

    function test_forcedTransferValue_revertsForDifferentSlots() public {
        vm.startPrank(registry);
        uint256 tokenA = token.mint(alice, SLOT_BONDS, 1000e18);
        uint256 tokenB = token.mint(bob, 2, 0);
        vm.expectRevert("EwpgERC3525: tokens in different slots");
        token.forcedTransferValue(tokenA, tokenB, 100e18, "err");
        vm.stopPrank();
    }

    /// @notice Regression test for the §26 Einziehung no-op bug: forceBurnValue used to
    ///         emit ForcedValueBurn without ever reducing the token's balance. Assert the
    ///         balance actually drops by exactly `value`, not just that the event fired.
    function test_forceBurnValue_reducesBalance() public {
        vm.startPrank(registry);
        uint256 tokenId = token.mint(alice, SLOT_BONDS, 1000e18);

        vm.expectEmit(true, false, false, true, address(token));
        emit EwpgERC3525.ForcedValueBurn(tokenId, 400e18, unicode"BaFin Einziehungsverfügung");
        token.forceBurnValue(tokenId, 400e18, unicode"BaFin Einziehungsverfügung");
        vm.stopPrank();

        assertEq(token.balanceOf(tokenId), 600e18);
    }

    function test_forceBurnValue_revertsOnInsufficientBalance() public {
        vm.startPrank(registry);
        uint256 tokenId = token.mint(alice, SLOT_BONDS, 100e18);
        vm.expectRevert("EwpgERC3525: insufficient balance");
        token.forceBurnValue(tokenId, 200e18, "err");
        vm.stopPrank();
    }

    function test_forceBurnValue_bypassesPauseAndFreeze() public {
        vm.startPrank(registry);
        uint256 tokenId = token.mint(alice, SLOT_BONDS, 1000e18);
        token.pauseSlot(SLOT_BONDS);
        token.freezeToken(tokenId, "sanctions hold");
        token.forceBurnValue(tokenId, 300e18, unicode"BaFin Einziehungsverfügung");
        vm.stopPrank();

        assertEq(token.balanceOf(tokenId), 700e18);
    }

    // ── Slot metadata ─────────────────────────────────────────────────────────

    function test_setSlotMetadataHash_emitsEvent() public {
        bytes32 hash = keccak256("coupon=0.05,maturity=2030-12-01");
        vm.prank(registry);
        vm.expectEmit(true, false, false, true);
        emit EwpgERC3525.SlotMetadataHashSet(SLOT_BONDS, hash);
        token.setSlotMetadataHash(SLOT_BONDS, hash);
    }

    // ── Access control ────────────────────────────────────────────────────────

    function test_adminFunctions_revertForNonRegistry() public {
        vm.startPrank(mallory);
        vm.expectRevert();
        token.pauseSlot(SLOT_BONDS);
        vm.expectRevert();
        token.freezeToken(1, "hack");
        vm.stopPrank();
    }

    // ── Factory dispatch ──────────────────────────────────────────────────────

    function test_factory_deploysErc3525ViaTokenType3() public {
        factory = new AssetTokenFactory(registry);
        address tokenAddr = factory.deployToken(3, "Bond", "BND", ASSET_ID);
        assertFalse(tokenAddr == address(0));
        EwpgERC3525 deployed = EwpgERC3525(tokenAddr);
        assertEq(deployed.assetId(), ASSET_ID);
    }
}
