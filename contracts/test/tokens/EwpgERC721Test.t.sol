// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../../src/tokens/EwpgERC721.sol";

contract EwpgERC721Test is Test {
    EwpgERC721 token;
    address registry = address(0x1);
    address alice = address(0x2);
    address bob = address(0x3);
    bytes32 assetId = keccak256("asset-nft-1");
    uint256 constant TOKEN_ID_1 = 1;
    uint256 constant TOKEN_ID_2 = 2;

    function setUp() public {
        vm.prank(registry);
        token = new EwpgERC721("Test NFT Security Token", "TNST", registry, assetId);
    }

    // -------------------------------------------------------------------------
    // Deployment / metadata
    // -------------------------------------------------------------------------

    function test_metadata() public view {
        assertEq(token.name(), "Test NFT Security Token");
        assertEq(token.symbol(), "TNST");
        assertEq(token.assetId(), assetId);
        assertEq(token.registry(), registry);
    }

    // -------------------------------------------------------------------------
    // Minting
    // -------------------------------------------------------------------------

    function test_mint_revertsIfRecipientNotWhitelisted() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC721: recipient not whitelisted");
        token.mint(alice, TOKEN_ID_1);
    }

    function test_mint_succeedsForWhitelistedAddress() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();
        assertEq(token.ownerOf(TOKEN_ID_1), alice);
        assertEq(token.balanceOf(alice), 1);
    }

    function test_mint_revertsIfCalledByNonRegistry() public {
        vm.prank(registry);
        token.whitelist(alice);

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.mint(alice, TOKEN_ID_1);
    }

    function test_mint_revertsIfTokenAlreadyMinted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        vm.expectRevert();
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();
    }

    // -------------------------------------------------------------------------
    // Burning
    // -------------------------------------------------------------------------

    function test_burn_succeeds() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        token.burn(TOKEN_ID_1);
        vm.stopPrank();
        assertEq(token.balanceOf(alice), 0);
        vm.expectRevert();
        token.ownerOf(TOKEN_ID_1);
    }

    function test_burn_revertsIfCalledByNonRegistry() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.burn(TOKEN_ID_1);
    }

    function test_burn_revertsIfTokenDoesNotExist() public {
        vm.prank(registry);
        vm.expectRevert();
        token.burn(999);
    }

    // -------------------------------------------------------------------------
    // Transfers
    // -------------------------------------------------------------------------

    function test_transfer_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC721: recipient not whitelisted");
        token.transferFrom(alice, bob, TOKEN_ID_1);
    }

    function test_transfer_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();

        vm.prank(alice);
        token.transferFrom(alice, bob, TOKEN_ID_1);
        assertEq(token.ownerOf(TOKEN_ID_1), bob);
        assertEq(token.balanceOf(alice), 0);
        assertEq(token.balanceOf(bob), 1);
    }

    function test_safeTransfer_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC721: recipient not whitelisted");
        token.safeTransferFrom(alice, bob, TOKEN_ID_1);
    }

    function test_approvedTransfer_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, TOKEN_ID_1);
        vm.stopPrank();

        vm.prank(alice);
        token.approve(bob, TOKEN_ID_1);

        vm.prank(bob);
        token.transferFrom(alice, bob, TOKEN_ID_1);
        assertEq(token.ownerOf(TOKEN_ID_1), bob);
    }

    function test_removeFromWhitelist_preventsIncomingTransfer() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, TOKEN_ID_1);
        token.removeFromWhitelist(bob);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC721: recipient not whitelisted");
        token.transferFrom(alice, bob, TOKEN_ID_1);
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

    // -------------------------------------------------------------------------
    // Multiple tokens
    // -------------------------------------------------------------------------

    function test_mintMultipleTokens() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, TOKEN_ID_1);
        token.mint(alice, TOKEN_ID_2);
        vm.stopPrank();
        assertEq(token.balanceOf(alice), 2);
        assertEq(token.ownerOf(TOKEN_ID_1), alice);
        assertEq(token.ownerOf(TOKEN_ID_2), alice);
    }
}
