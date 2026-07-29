// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/factory/ITREXFactory.sol";

import "../../src/tokens/EwpgERC3643.sol";
import "../helpers/TrexSuiteDeployer.sol";

/// @dev T-REX proxies use `OwnableOnceNext2StepUpgradeable` — the factory's
///      `transferOwnership` only sets a pending owner; the recipient must call
///      `acceptOwnership()` before any `onlyOwner`/`onlyAgent`-adjacent call works.
interface IOwnable2Step {
    function acceptOwnership() external;
}

/// @notice Exercises the Registerwerk-specific `forcedApprove` extension added to the
///         T-REX (ERC-3643) token — T-REX has no native forcedApprove; every other Ewpg
///         token standard already has one (see `IEwpgAdminControls.forcedApprove`), this
///         brings the T-REX suite to parity.
///
///         `forcedApprove` only sets an ERC-20 allowance via T-REX's internal `_approve`
///         (no compliance/whitelist check involved — same as `IERC20.approve`), so unlike
///         `EwpgBondDeskTest` this suite does not need real ONCHAINID-verified investors
///         or minted balances: a minimal T-REX suite with no compliance modules is enough.
contract EwpgERC3643ForcedApproveTest is Test {
    address internal operator = address(0x1);
    address internal owner = address(0x2);
    address internal spender = address(0x3);
    address internal stranger = address(0x4);

    IERC3643 internal token;

    function setUp() public {
        TrexDeployment memory trex = TrexSuiteDeployer.deploy(operator);

        address[] memory irAgents = new address[](1);
        irAgents[0] = operator;
        address[] memory tokenAgents = new address[](1);
        tokenAgents[0] = operator;

        ITREXFactory.TokenDetails memory tokenDetails = ITREXFactory.TokenDetails({
            owner: operator,
            name: "Test T-REX Bond",
            symbol: "TTRB",
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: irAgents,
            tokenAgents: tokenAgents,
            complianceModules: new address[](0),
            complianceSettings: new bytes[](0)
        });

        uint256[] memory claimTopics = new uint256[](1);
        claimTopics[0] = 1; // KYC
        address[] memory issuers = new address[](1);
        issuers[0] = address(0x999); // never invoked — no isVerified check in this suite
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = claimTopics;

        ITREXFactory.ClaimDetails memory claimDetails =
            ITREXFactory.ClaimDetails({claimTopics: claimTopics, issuers: issuers, issuerClaims: issuerClaims});

        vm.prank(operator);
        address tokenAddress = trex.factory.deployEwpgSuite(keccak256("forced-approve-test"), "forced-approve-test", tokenDetails, claimDetails);

        token = IERC3643(tokenAddress);

        // `operator` is already a token agent via `tokenAgents` in TokenDetails above;
        // it only needs to accept the pending ownership transfer T-REX's factory made.
        vm.prank(operator);
        IOwnable2Step(tokenAddress).acceptOwnership();
    }

    function test_forcedApprove_setsAllowance() public {
        vm.prank(operator);
        EwpgERC3643(address(token)).forcedApprove(owner, spender, 500e18, "BaFin Az. 2026-001");

        assertEq(token.allowance(owner, spender), 500e18);
    }

    function test_forcedApprove_emitsForcedApprove() public {
        vm.expectEmit(true, true, false, true, address(token));
        emit EwpgERC3643.ForcedApprove(owner, spender, 500e18, "BaFin Az. 2026-001");

        vm.prank(operator);
        EwpgERC3643(address(token)).forcedApprove(owner, spender, 500e18, "BaFin Az. 2026-001");
    }

    function test_forcedApprove_overridesExistingAllowance() public {
        vm.startPrank(operator);
        EwpgERC3643(address(token)).forcedApprove(owner, spender, 500e18, "BaFin Az. 2026-001");
        EwpgERC3643(address(token)).forcedApprove(owner, spender, 100e18, "BaFin Az. 2026-002 (correction)");
        vm.stopPrank();

        assertEq(token.allowance(owner, spender), 100e18);
    }

    function test_forcedApprove_revertsForNonAgent() public {
        vm.prank(stranger);
        vm.expectRevert();
        EwpgERC3643(address(token)).forcedApprove(owner, spender, 1, "unauthorized");
    }

    function test_forcedApprove_revertsOnZeroOwner() public {
        vm.prank(operator);
        vm.expectRevert();
        EwpgERC3643(address(token)).forcedApprove(address(0), spender, 1, "x");
    }

    function test_forcedApprove_revertsOnZeroSpender() public {
        vm.prank(operator);
        vm.expectRevert();
        EwpgERC3643(address(token)).forcedApprove(owner, address(0), 1, "x");
    }
}
