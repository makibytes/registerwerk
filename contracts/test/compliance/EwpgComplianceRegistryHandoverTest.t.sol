// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/tokens/EwpgERC20.sol";

/// @title EwpgComplianceRegistryHandoverTest
/// @notice Verifies the two-step registry handover added to EwpgCompliance:
///         without it, rotating or losing the registry key would leave every
///         deployed token permanently without pause/freeze/forcedTransfer.
contract EwpgComplianceRegistryHandoverTest is Test {
    EwpgERC20 token;
    address registry = address(0x1);
    address newRegistry = address(0x2);
    address stranger = address(0x99);
    address investor = address(0x10);

    function setUp() public {
        token = new EwpgERC20("Test Bond", "TBND", registry, bytes32("asset-1"));
    }

    // -------------------------------------------------------------------------
    // transferRegistry
    // -------------------------------------------------------------------------

    function test_onlyRegistryCanStartHandover() public {
        vm.prank(stranger);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.transferRegistry(newRegistry);
    }

    function test_handoverToZeroAddressReverts() public {
        vm.prank(registry);
        vm.expectRevert("EwpgCompliance: zero registry address");
        token.transferRegistry(address(0));
    }

    function test_startHandoverSetsPendingButKeepsAuthority() public {
        vm.prank(registry);
        token.transferRegistry(newRegistry);

        assertEq(token.pendingRegistry(), newRegistry);
        assertEq(token.registry(), registry);

        // Current registry retains full control until acceptance.
        vm.prank(registry);
        token.pause();
        assertTrue(token.isPaused());
    }

    // -------------------------------------------------------------------------
    // acceptRegistry
    // -------------------------------------------------------------------------

    function test_onlyPendingRegistryCanAccept() public {
        vm.prank(registry);
        token.transferRegistry(newRegistry);

        vm.prank(stranger);
        vm.expectRevert("EwpgCompliance: caller is not pending registry");
        token.acceptRegistry();
    }

    function test_acceptMovesAuthorityAndClearsPending() public {
        vm.prank(registry);
        token.transferRegistry(newRegistry);

        vm.prank(newRegistry);
        token.acceptRegistry();

        assertEq(token.registry(), newRegistry);
        assertEq(token.pendingRegistry(), address(0));

        // New registry has full admin power...
        vm.startPrank(newRegistry);
        token.whitelist(investor);
        token.mint(investor, 100);
        vm.stopPrank();
        assertEq(token.balanceOf(investor), 100);

        // ...and the old registry has none.
        vm.prank(registry);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.pause();
    }

    function test_acceptWithoutPendingReverts() public {
        vm.prank(stranger);
        vm.expectRevert("EwpgCompliance: caller is not pending registry");
        token.acceptRegistry();
    }

    // -------------------------------------------------------------------------
    // cancelRegistryTransfer
    // -------------------------------------------------------------------------

    function test_cancelClearsPendingNomination() public {
        vm.prank(registry);
        token.transferRegistry(newRegistry);

        vm.prank(registry);
        token.cancelRegistryTransfer();

        assertEq(token.pendingRegistry(), address(0));

        vm.prank(newRegistry);
        vm.expectRevert("EwpgCompliance: caller is not pending registry");
        token.acceptRegistry();
    }

    function test_onlyRegistryCanCancel() public {
        vm.prank(registry);
        token.transferRegistry(newRegistry);

        vm.prank(stranger);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        token.cancelRegistryTransfer();
    }

    // -------------------------------------------------------------------------
    // Re-nomination overwrites
    // -------------------------------------------------------------------------

    function test_renominationOverwritesPending() public {
        address third = address(0x3);
        vm.startPrank(registry);
        token.transferRegistry(newRegistry);
        token.transferRegistry(third);
        vm.stopPrank();

        vm.prank(newRegistry);
        vm.expectRevert("EwpgCompliance: caller is not pending registry");
        token.acceptRegistry();

        vm.prank(third);
        token.acceptRegistry();
        assertEq(token.registry(), third);
    }
}
