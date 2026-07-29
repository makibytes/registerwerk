// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "./mocks/MockClaimIssuer.sol";
import "./mocks/MockOnchainId.sol";

/// @notice Reference ecosystem dApp: a minimal loan desk. Serves as living documentation
///         for marketplace publishers — a dApp needs nothing but the oracle address and
///         the {RegisterwerkGated} modifiers to enforce Registerwerk identity/permissions.
contract SampleLoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");
    uint256 public constant TOPIC_KYC = 1;

    uint256 public loansOpened;

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(TOPIC_KYC) {
        loansOpened += 1;
    }

    function ping() external requiresActiveMember {}
}

contract SampleGatedDappTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    SampleLoanDesk dapp;
    MockOnchainId orgId;
    MockClaimIssuer issuer;

    address operator = address(0x1);
    address alice = address(0x3);
    address mallory = address(0x66);

    bytes32 permOpen;
    uint256 topicKyc;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        dapp = new SampleLoanDesk(oracle);
        orgId = new MockOnchainId();
        issuer = new MockClaimIssuer();
        permOpen = dapp.OPEN_LOAN();
        topicKyc = dapp.TOPIC_KYC();

        // full happy-path wiring: registered org, bound member, org grant, trusted KYC claim
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), permOpen);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicKyc;
        tir.addTrustedIssuer(address(issuer), topics);
        vm.stopPrank();
        orgId.addClaim(topicKyc, address(issuer), hex"01", hex"02");
    }

    function test_openLoan_succeedsForVerifiedMember() public {
        vm.prank(alice);
        dapp.openLoan();
        assertEq(dapp.loansOpened(), 1);
    }

    function test_openLoan_revertsForUnboundWallet() public {
        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, permOpen)
        );
        dapp.openLoan();
    }

    function test_openLoan_revertsWhileOrgSuspended() public {
        vm.prank(operator);
        orgRegistry.suspendOrg(address(orgId), "sanctions review");

        vm.prank(alice);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, permOpen)
        );
        dapp.openLoan();
    }

    function test_openLoan_revertsAfterGrantRevocation() public {
        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), permOpen);

        vm.prank(alice);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, permOpen)
        );
        dapp.openLoan();
    }

    function test_openLoan_revertsWhenClaimRevokedByIssuer() public {
        issuer.setValid(false);

        vm.prank(alice);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, topicKyc)
        );
        dapp.openLoan();
    }

    function test_openLoan_respectsRoleRestriction() public {
        vm.prank(operator);
        permissions.setRoleRestricted(address(orgId), permOpen, true);

        // TRADER role has no role grant yet → denied
        vm.prank(alice);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, permOpen)
        );
        dapp.openLoan();

        vm.prank(operator);
        permissions.grantToRole(address(orgId), keccak256("TRADER"), permOpen);
        vm.prank(alice);
        dapp.openLoan();
        assertEq(dapp.loansOpened(), 1);
    }

    function test_ping_requiresActiveMember() public {
        vm.prank(alice);
        dapp.ping();

        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.NotAnActiveMember.selector, mallory)
        );
        dapp.ping();
    }
}
