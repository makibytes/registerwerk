// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../../src/tokens/EwpgERC1155.sol";

contract EwpgERC1155Test is Test {
    EwpgERC1155 token;
    address registry = address(0x1);
    address alice = address(0x2);
    address bob = address(0x3);
    bytes32 assetId = keccak256("asset-multi-1");
    uint256 constant ID_A = 1;
    uint256 constant ID_B = 2;

    function setUp() public {
        vm.prank(registry);
        token = new EwpgERC1155("EMTK", registry, assetId);
    }

    // -------------------------------------------------------------------------
    // Deployment / metadata
    // -------------------------------------------------------------------------

    function test_metadata() public view {
        assertEq(token.symbol(), "EMTK");
        assertEq(token.assetId(), assetId);
        assertEq(token.registry(), registry);
    }

    // -------------------------------------------------------------------------
    // Minting (single)
    // -------------------------------------------------------------------------

    function test_mint_revertsIfRecipientNotWhitelisted() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC1155: recipient not whitelisted");
        token.mint(alice, ID_A, 100, "");
    }

    function test_mint_succeedsForWhitelistedAddress() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 100, "");
        vm.stopPrank();
        assertEq(token.balanceOf(alice, ID_A), 100);
    }

    function test_mint_revertsIfCalledByNonRegistry() public {
        vm.prank(registry);
        token.whitelist(alice);

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.mint(alice, ID_A, 100, "");
    }

    // -------------------------------------------------------------------------
    // Batch minting
    // -------------------------------------------------------------------------

    function test_mintBatch_revertsIfRecipientNotWhitelisted() public {
        uint256[] memory ids = new uint256[](2);
        ids[0] = ID_A;
        ids[1] = ID_B;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 50;
        amounts[1] = 75;

        vm.prank(registry);
        vm.expectRevert("EwpgERC1155: recipient not whitelisted");
        token.mintBatch(alice, ids, amounts, "");
    }

    function test_mintBatch_succeeds() public {
        uint256[] memory ids = new uint256[](2);
        ids[0] = ID_A;
        ids[1] = ID_B;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 50;
        amounts[1] = 75;

        vm.startPrank(registry);
        token.whitelist(alice);
        token.mintBatch(alice, ids, amounts, "");
        vm.stopPrank();

        assertEq(token.balanceOf(alice, ID_A), 50);
        assertEq(token.balanceOf(alice, ID_B), 75);
    }

    function test_mintBatch_revertsIfCalledByNonRegistry() public {
        uint256[] memory ids = new uint256[](1);
        ids[0] = ID_A;
        uint256[] memory amounts = new uint256[](1);
        amounts[0] = 10;

        vm.prank(registry);
        token.whitelist(alice);

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.mintBatch(alice, ids, amounts, "");
    }

    // -------------------------------------------------------------------------
    // Burning
    // -------------------------------------------------------------------------

    function test_burn_succeeds() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 100, "");
        token.burn(alice, ID_A, 40);
        vm.stopPrank();
        assertEq(token.balanceOf(alice, ID_A), 60);
    }

    function test_burn_revertsIfCalledByNonRegistry() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 100, "");
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.burn(alice, ID_A, 10);
    }

    function test_burn_revertsIfInsufficientBalance() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 10, "");
        vm.expectRevert();
        token.burn(alice, ID_A, 50);
        vm.stopPrank();
    }

    // -------------------------------------------------------------------------
    // Transfers
    // -------------------------------------------------------------------------

    function test_safeTransferFrom_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 100, "");
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC1155: recipient not whitelisted");
        token.safeTransferFrom(alice, bob, ID_A, 10, "");
    }

    function test_safeTransferFrom_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, ID_A, 100, "");
        vm.stopPrank();

        vm.prank(alice);
        token.safeTransferFrom(alice, bob, ID_A, 30, "");
        assertEq(token.balanceOf(alice, ID_A), 70);
        assertEq(token.balanceOf(bob, ID_A), 30);
    }

    function test_safeBatchTransferFrom_revertsIfRecipientNotWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.mint(alice, ID_A, 100, "");
        token.mint(alice, ID_B, 200, "");
        vm.stopPrank();

        uint256[] memory ids = new uint256[](2);
        ids[0] = ID_A;
        ids[1] = ID_B;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 10;
        amounts[1] = 20;

        vm.prank(alice);
        vm.expectRevert("EwpgERC1155: recipient not whitelisted");
        token.safeBatchTransferFrom(alice, bob, ids, amounts, "");
    }

    function test_safeBatchTransferFrom_succeedsIfBothWhitelisted() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, ID_A, 100, "");
        token.mint(alice, ID_B, 200, "");
        vm.stopPrank();

        uint256[] memory ids = new uint256[](2);
        ids[0] = ID_A;
        ids[1] = ID_B;
        uint256[] memory amounts = new uint256[](2);
        amounts[0] = 10;
        amounts[1] = 20;

        vm.prank(alice);
        token.safeBatchTransferFrom(alice, bob, ids, amounts, "");
        assertEq(token.balanceOf(bob, ID_A), 10);
        assertEq(token.balanceOf(bob, ID_B), 20);
    }

    function test_removeFromWhitelist_preventsTransfer() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, ID_A, 100, "");
        token.removeFromWhitelist(bob);
        vm.stopPrank();

        vm.prank(alice);
        vm.expectRevert("EwpgERC1155: recipient not whitelisted");
        token.safeTransferFrom(alice, bob, ID_A, 10, "");
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
    // Forced-operation reentrancy / compliance-bypass hardening
    // -------------------------------------------------------------------------

    /// A malicious recipient of a §24 forced transfer must not be able to piggyback
    /// on the compliance-bypass window to move its own frozen tokens out. Before the
    /// fix, _safeTransferFrom invoked onERC1155Received while _inForceOp was still true,
    /// letting the receiver re-enter and defeat the freeze. The forced path now moves
    /// balances via _update, so the receiver hook is never called.
    function test_forcedTransfer_doesNotInvokeReceiverHook_noComplianceBypass() public {
        ReentrantReceiver attacker = new ReentrantReceiver();
        address clean = address(0xCAFE);

        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(address(attacker));
        token.mint(alice, ID_A, 100, "");                 // victim's holding to be corrected
        token.mint(address(attacker), ID_B, 50, "");       // attacker's own holding
        token.freezeAddress(address(attacker), "sanctions"); // attacker is frozen
        vm.stopPrank();

        // Arm the attacker to try to escape its frozen ID_B to a clean wallet if its
        // receiver hook is ever invoked during the forced-op window.
        attacker.arm(token, clean, ID_B, 50);

        // Registry performs a court-ordered §24 correction crediting the attacker.
        vm.prank(registry);
        token.forcedTransferSingle(alice, address(attacker), ID_A, 100, "BaFin Az. 2025-001");

        assertFalse(attacker.reentered(), "receiver hook must not fire during forced transfer");
        assertEq(token.balanceOf(address(attacker), ID_A), 100, "correction credited");
        assertEq(token.balanceOf(alice, ID_A), 0, "source debited");
        // The freeze held: the attacker's own tokens did not escape.
        assertEq(token.balanceOf(address(attacker), ID_B), 50, "frozen tokens must not move");
        assertEq(token.balanceOf(clean, ID_B), 0, "no bypass transfer occurred");
    }

    /// Regression: forced batch transfer still moves balances correctly.
    function test_forcedTransferBatch_movesBalances() public {
        vm.startPrank(registry);
        token.whitelist(alice);
        token.whitelist(bob);
        token.mint(alice, ID_A, 100, "");
        token.mint(alice, ID_B, 40, "");

        uint256[] memory ids = new uint256[](2);
        uint256[] memory amounts = new uint256[](2);
        ids[0] = ID_A; ids[1] = ID_B;
        amounts[0] = 30; amounts[1] = 10;
        token.forcedTransferBatch(alice, bob, ids, amounts, "BaFin Az. 2025-002");
        vm.stopPrank();

        assertEq(token.balanceOf(bob, ID_A), 30);
        assertEq(token.balanceOf(bob, ID_B), 10);
        assertEq(token.balanceOf(alice, ID_A), 70);
        assertEq(token.balanceOf(alice, ID_B), 30);
    }
}

/// Malicious ERC-1155 recipient that, on receiving tokens, attempts to move a set of
/// its own (frozen) tokens to a clean wallet — the classic compliance-bypass reentrancy.
contract ReentrantReceiver {
    EwpgERC1155 private token;
    address private clean;
    uint256 private escapeId;
    uint256 private escapeAmount;
    bool private armed;
    bool public reentered;

    /// Arm the escape after setup mints, so the receiver only tries to re-enter
    /// during the forced transfer under test — not while it is being funded.
    function arm(EwpgERC1155 _token, address _clean, uint256 _id, uint256 _amount) external {
        token = _token;
        clean = _clean;
        escapeId = _id;
        escapeAmount = _amount;
        armed = true;
    }

    function onERC1155Received(address, address, uint256, uint256, bytes calldata)
        external
        returns (bytes4)
    {
        if (armed) {
            reentered = true;
            token.safeTransferFrom(address(this), clean, escapeId, escapeAmount, "");
        }
        return this.onERC1155Received.selector;
    }

    function onERC1155BatchReceived(address, address, uint256[] calldata, uint256[] calldata, bytes calldata)
        external
        returns (bytes4)
    {
        reentered = true;
        return this.onERC1155BatchReceived.selector;
    }

    function supportsInterface(bytes4) external pure returns (bool) {
        return true;
    }
}
