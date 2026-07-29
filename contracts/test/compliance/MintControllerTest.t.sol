// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/compliance/MintController.sol";

contract MintControllerTest is Test {
    MintController controller;
    address owner = address(0x1);
    address tokenA = address(0x10);
    address tokenB = address(0x11);
    address stranger = address(0x99);

    function setUp() public {
        vm.prank(owner);
        controller = new MintController();
    }

    // -------------------------------------------------------------------------
    // Deployment
    // -------------------------------------------------------------------------

    function test_ownerIsSetOnDeployment() public view {
        assertEq(controller.owner(), owner);
    }

    function test_defaultAllowanceIsZero() public view {
        assertEq(controller.getMintAllowance(tokenA), 0);
    }

    function test_defaultAutoApproveTransferIsFalse() public view {
        assertFalse(controller.autoApproveTransfer(tokenA));
    }

    function test_defaultAutoApproveBurnIsFalse() public view {
        assertFalse(controller.autoApproveBurn(tokenA));
    }

    // -------------------------------------------------------------------------
    // setMintAllowance
    // -------------------------------------------------------------------------

    function test_setMintAllowance_setsValue() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 1000e18);
        assertEq(controller.getMintAllowance(tokenA), 1000e18);
    }

    function test_setMintAllowance_emitsEvent() public {
        vm.prank(owner);
        vm.expectEmit(true, false, false, true);
        emit IMintController.MintAllowanceSet(tokenA, 500e18);
        controller.setMintAllowance(tokenA, 500e18);
    }

    function test_setMintAllowance_revertsForZeroAddress() public {
        vm.prank(owner);
        vm.expectRevert("MintController: zero address");
        controller.setMintAllowance(address(0), 1000e18);
    }

    function test_setMintAllowance_revertsIfCalledByNonOwner() public {
        vm.prank(stranger);
        vm.expectRevert();
        controller.setMintAllowance(tokenA, 1000e18);
    }

    function test_setMintAllowance_canOverwriteExistingAllowance() public {
        vm.startPrank(owner);
        controller.setMintAllowance(tokenA, 1000e18);
        controller.setMintAllowance(tokenA, 500e18);
        vm.stopPrank();
        assertEq(controller.getMintAllowance(tokenA), 500e18);
    }

    function test_setMintAllowance_isIndependentPerTarget() public {
        vm.startPrank(owner);
        controller.setMintAllowance(tokenA, 100e18);
        controller.setMintAllowance(tokenB, 200e18);
        vm.stopPrank();
        assertEq(controller.getMintAllowance(tokenA), 100e18);
        assertEq(controller.getMintAllowance(tokenB), 200e18);
    }

    // -------------------------------------------------------------------------
    // consumeMintAllowance
    // -------------------------------------------------------------------------

    function test_consumeMintAllowance_deductsFromAllowance() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 1000e18);

        vm.prank(tokenA);
        controller.consumeMintAllowance(tokenA, 300e18);
        assertEq(controller.getMintAllowance(tokenA), 700e18);
    }

    function test_consumeMintAllowance_emitsEvent() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 1000e18);

        vm.prank(tokenA);
        vm.expectEmit(true, false, false, true);
        emit IMintController.MintAllowanceConsumed(tokenA, 400e18, 600e18);
        controller.consumeMintAllowance(tokenA, 400e18);
    }

    function test_consumeMintAllowance_revertsIfAllowanceExceeded() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 100e18);

        vm.prank(tokenA);
        vm.expectRevert("MintController: allowance exceeded");
        controller.consumeMintAllowance(tokenA, 200e18);
    }

    function test_consumeMintAllowance_revertsIfTargetIsNotCaller() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 1000e18);

        // tokenB tries to consume tokenA's allowance — must revert.
        vm.prank(tokenB);
        vm.expectRevert("MintController: target must be caller");
        controller.consumeMintAllowance(tokenA, 100e18);
    }

    function test_consumeMintAllowance_multipleConsumptions() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 1000e18);

        vm.startPrank(tokenA);
        controller.consumeMintAllowance(tokenA, 300e18);
        controller.consumeMintAllowance(tokenA, 300e18);
        controller.consumeMintAllowance(tokenA, 300e18);
        vm.stopPrank();
        assertEq(controller.getMintAllowance(tokenA), 100e18);
    }

    function test_consumeMintAllowance_exactAllowanceSucceeds() public {
        vm.prank(owner);
        controller.setMintAllowance(tokenA, 500e18);

        vm.prank(tokenA);
        controller.consumeMintAllowance(tokenA, 500e18);
        assertEq(controller.getMintAllowance(tokenA), 0);
    }

    // -------------------------------------------------------------------------
    // setAutoApproveTransfer
    // -------------------------------------------------------------------------

    function test_setAutoApproveTransfer_setsTrue() public {
        vm.prank(owner);
        controller.setAutoApproveTransfer(tokenA, true);
        assertTrue(controller.autoApproveTransfer(tokenA));
    }

    function test_setAutoApproveTransfer_setsFalse() public {
        vm.startPrank(owner);
        controller.setAutoApproveTransfer(tokenA, true);
        controller.setAutoApproveTransfer(tokenA, false);
        vm.stopPrank();
        assertFalse(controller.autoApproveTransfer(tokenA));
    }

    function test_setAutoApproveTransfer_emitsEvent() public {
        vm.prank(owner);
        vm.expectEmit(true, false, false, true);
        emit IMintController.AutoApproveTransferSet(tokenA, true);
        controller.setAutoApproveTransfer(tokenA, true);
    }

    function test_setAutoApproveTransfer_revertsForZeroAddress() public {
        vm.prank(owner);
        vm.expectRevert("MintController: zero address");
        controller.setAutoApproveTransfer(address(0), true);
    }

    function test_setAutoApproveTransfer_revertsIfCalledByNonOwner() public {
        vm.prank(stranger);
        vm.expectRevert();
        controller.setAutoApproveTransfer(tokenA, true);
    }

    // -------------------------------------------------------------------------
    // setAutoApproveBurn
    // -------------------------------------------------------------------------

    function test_setAutoApproveBurn_setsTrue() public {
        vm.prank(owner);
        controller.setAutoApproveBurn(tokenA, true);
        assertTrue(controller.autoApproveBurn(tokenA));
    }

    function test_setAutoApproveBurn_setsFalse() public {
        vm.startPrank(owner);
        controller.setAutoApproveBurn(tokenA, true);
        controller.setAutoApproveBurn(tokenA, false);
        vm.stopPrank();
        assertFalse(controller.autoApproveBurn(tokenA));
    }

    function test_setAutoApproveBurn_emitsEvent() public {
        vm.prank(owner);
        vm.expectEmit(true, false, false, true);
        emit IMintController.AutoApproveBurnSet(tokenA, true);
        controller.setAutoApproveBurn(tokenA, true);
    }

    function test_setAutoApproveBurn_revertsForZeroAddress() public {
        vm.prank(owner);
        vm.expectRevert("MintController: zero address");
        controller.setAutoApproveBurn(address(0), true);
    }

    function test_setAutoApproveBurn_revertsIfCalledByNonOwner() public {
        vm.prank(stranger);
        vm.expectRevert();
        controller.setAutoApproveBurn(tokenA, true);
    }

    // -------------------------------------------------------------------------
    // Independence of flags per target
    // -------------------------------------------------------------------------

    function test_flagsAreIndependentPerTarget() public {
        vm.startPrank(owner);
        controller.setAutoApproveTransfer(tokenA, true);
        controller.setAutoApproveBurn(tokenB, true);
        vm.stopPrank();
        assertTrue(controller.autoApproveTransfer(tokenA));
        assertFalse(controller.autoApproveBurn(tokenA));
        assertFalse(controller.autoApproveTransfer(tokenB));
        assertTrue(controller.autoApproveBurn(tokenB));
    }
}
