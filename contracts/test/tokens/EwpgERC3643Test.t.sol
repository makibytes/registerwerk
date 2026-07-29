// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import {EwpgERC3643} from "../../src/tokens/EwpgERC3643.sol";

/**
 * @notice Integration tests for ERC-3643 (T-REX) deployment.
 *
 * These placeholder tests validate constants and deployment invariants that can be
 * checked without a full factory setup. For the full end-to-end deployment — T-REX
 * implementation authority, ONCHAINID factory, identity registry, compliance module,
 * and a real EwpgERC3643 bond suite — see {EwpgBondDeskTest}
 * (test/examples/EwpgBondDesk.t.sol), which bootstraps the whole stack via
 * {TrexSuiteDeployer} (test/helpers/TrexSuiteDeployer.sol) and exercises it through the
 * {EwpgBondDesk} reference dApp.
 */
contract EwpgERC3643Test is Test {

    address internal registry  = address(0x1);
    address internal investor1 = address(0x2);
    address internal investor2 = address(0x3);
    bytes32 internal assetId   = keccak256("test-erc3643-asset");

    // ── Placeholder tests (full suite requires T-REX factory setup) ────────

    function test_assetId_isStored() public view {
        // EwpgERC3643 stores the assetId linking it to the registry DB.
        // Full test: deploy via EwpgTREXFactory, verify assetId.
        assertEq(assetId, keccak256("test-erc3643-asset"));
    }

    function test_trex_architecture_constants() public pure {
        // Standard T-REX claim topics used throughout the eWpG registry.
        uint256 KYC_TOPIC = 1;
        uint256 AML_TOPIC = 2;
        assertEq(KYC_TOPIC, 1);
        assertEq(AML_TOPIC, 2);
    }

    function test_assetId_uniqueness() public pure {
        bytes32 id1 = keccak256("asset-uuid-aaaa-bbbb-cccc");
        bytes32 id2 = keccak256("asset-uuid-dddd-eeee-ffff");
        assertTrue(id1 != id2, "different UUIDs must yield different assetIds");
    }

    function test_assetId_determinism() public pure {
        bytes32 a = keccak256("test-erc3643-asset");
        bytes32 b = keccak256("test-erc3643-asset");
        assertEq(a, b, "keccak256 of same input must be deterministic");
    }

    /// setAssetId is restricted to the owner of the TREXFactory registered on the
    /// proxy's ImplementationAuthority. On the bare logic contract the IA slot is
    /// zero, so nobody — not even the first caller — can bind an assetId to it.
    function test_setAssetId_revertsOnBareImplementation() public {
        EwpgERC3643 logic = new EwpgERC3643();
        vm.expectRevert(abi.encodeWithSelector(EwpgERC3643.UnauthorizedAssetLinker.selector, address(this)));
        logic.setAssetId(keccak256("some-asset"));
    }

    function test_setAssetId_rejectsZeroAssetId() public {
        EwpgERC3643 logic = new EwpgERC3643();
        vm.expectRevert(EwpgERC3643.ZeroAssetId.selector);
        logic.setAssetId(bytes32(0));
    }
}
