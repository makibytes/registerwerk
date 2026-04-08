// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../../src/confidential/ConfidentialERC3643.sol";

contract ConfidentialERC3643Test is Test {

    ConfidentialERC3643 token;
    address owner    = address(0x1);
    bytes32 assetId  = keccak256("conf-erc3643");

    function setUp() public {
        vm.prank(owner);
        token = new ConfidentialERC3643(
            assetId,
            "Confidential Security Token",
            "cSEC",
            address(0x10),  // identity registry (stub)
            address(0x11),  // compliance (stub)
            owner
        );
    }

    // ── Metadata ───────────────────────────────────────────────────────────

    function test_assetId_stored() public view {
        assertEq(token.assetId(), assetId);
    }

    function test_name_and_symbol() public view {
        assertEq(token.name(), "Confidential Security Token");
        assertEq(token.symbol(), "cSEC");
    }

    function test_decimals() public view {
        assertEq(token.decimals(), 6);
    }

    function test_initialIdentityRegistry() public view {
        assertEq(token.identityRegistry(), address(0x10));
    }

    function test_initialCompliance() public view {
        assertEq(token.compliance(), address(0x11));
    }

    // ── Pause / unpause ────────────────────────────────────────────────────

    function test_notPausedOnDeploy() public view {
        assertFalse(token.paused());
    }

    function test_pause_unpause() public {
        vm.prank(owner);
        token.pause();
        assertTrue(token.paused());

        vm.prank(owner);
        token.unpause();
        assertFalse(token.paused());
    }

    function test_onlyOwner_canPause() public {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.pause();
    }

    function test_onlyOwner_canUnpause() public {
        vm.prank(owner);
        token.pause();

        vm.prank(address(0x99));
        vm.expectRevert();
        token.unpause();
    }

    // ── Confidential transfer ──────────────────────────────────────────────

    function test_confidentialTransfer_emitsEvent() public {
        address recipient = address(0x2);
        vm.expectEmit(true, true, false, false);
        emit ConfidentialERC3643.ConfidentialTransfer(address(this), recipient);
        token.confidentialTransfer(recipient, hex"", hex"");
    }

    function test_confidentialTransfer_revertsWhenPaused() public {
        vm.prank(owner);
        token.pause();

        vm.expectRevert("ConfidentialERC3643: paused");
        token.confidentialTransfer(address(0x3), hex"", hex"");
    }

    // ── Confidential mint ──────────────────────────────────────────────────

    function test_confidentialMint_emitsEvent() public {
        address recipient = address(0x2);
        vm.prank(owner);
        vm.expectEmit(true, false, false, false);
        emit ConfidentialERC3643.ConfidentialMint(recipient);
        token.confidentialMint(recipient, hex"", hex"");
    }

    function test_onlyOwner_canMint() public {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.confidentialMint(address(0x2), hex"", hex"");
    }

    // ── Confidential burn ──────────────────────────────────────────────────

    function test_confidentialBurn_emitsEvent() public {
        address holder = address(0x2);
        vm.prank(owner);
        vm.expectEmit(true, false, false, false);
        emit ConfidentialERC3643.ConfidentialBurn(holder);
        token.confidentialBurn(holder, hex"", hex"");
    }

    function test_onlyOwner_canBurn() public {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.confidentialBurn(address(0x2), hex"", hex"");
    }

    // ── Encrypted balance stub ─────────────────────────────────────────────

    function test_getEncryptedBalance_returnsEmpty() public view {
        bytes memory result = token.getEncryptedBalance(address(0x2), bytes32(0), hex"");
        assertEq(result.length, 0, "stub must return empty bytes until fhEVM is installed");
    }

    // ── Admin setters ──────────────────────────────────────────────────────

    function test_setIdentityRegistry() public {
        address newIR = address(0x20);
        vm.prank(owner);
        token.setIdentityRegistry(newIR);
        assertEq(token.identityRegistry(), newIR);
    }

    function test_setCompliance() public {
        address newC = address(0x21);
        vm.prank(owner);
        token.setCompliance(newC);
        assertEq(token.compliance(), newC);
    }

    function test_onlyOwner_canSetIdentityRegistry() public {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.setIdentityRegistry(address(0x20));
    }
}
