// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/examples/BoardroomGovernance.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Exercises {BoardroomGovernance} — the reference dApp for the ONCHAINID
///         permission-management framework — end to end: org membership, operator
///         permission grants, org-admin role delegation (role restriction), and
///         per-action claim-topic checks (KYC vs. Accreditation).
contract BoardroomGovernanceTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    BoardroomGovernance dapp;

    MockOnchainId orgId;
    MockClaimIssuer issuerKyc;
    MockClaimIssuer issuerAccredited;

    address operator = address(0x1);
    address alice = address(0x3); // BOARD_SECRETARY
    address bob = address(0x4); // MEMBER
    address mallory = address(0x66); // unbound

    bytes32 constant MEMBER_ROLE = keccak256("MEMBER");
    bytes32 constant BOARD_SECRETARY_ROLE = keccak256("BOARD_SECRETARY");

    bytes32 propose_;
    bytes32 vote_;
    bytes32 tally_;
    uint256 topicKyc;
    uint256 topicAccredited;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        dapp = new BoardroomGovernance(oracle, address(0xBEEF));

        orgId = new MockOnchainId();
        issuerKyc = new MockClaimIssuer();
        issuerAccredited = new MockClaimIssuer();

        propose_ = dapp.PROPOSE();
        vote_ = dapp.VOTE();
        tally_ = dapp.TALLY();
        topicKyc = dapp.TOPIC_KYC();
        topicAccredited = dapp.TOPIC_ACCREDITED();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276); // Germany

        bytes32[] memory aliceRoles = new bytes32[](1);
        aliceRoles[0] = BOARD_SECRETARY_ROLE;
        orgRegistry.addMember(address(orgId), alice, aliceRoles, "");

        bytes32[] memory bobRoles = new bytes32[](1);
        bobRoles[0] = MEMBER_ROLE;
        orgRegistry.addMember(address(orgId), bob, bobRoles, "");

        permissions.grantToOrg(address(orgId), propose_);
        permissions.grantToOrg(address(orgId), vote_);
        permissions.grantToOrg(address(orgId), tally_);

        uint256[] memory kycTopics = new uint256[](1);
        kycTopics[0] = topicKyc;
        tir.addTrustedIssuer(address(issuerKyc), kycTopics);

        uint256[] memory accreditedTopics = new uint256[](1);
        accreditedTopics[0] = topicAccredited;
        tir.addTrustedIssuer(address(issuerAccredited), accreditedTopics);
        vm.stopPrank();

        orgId.addClaim(topicKyc, address(issuerKyc), hex"01", hex"02");
        orgId.addClaim(topicAccredited, address(issuerAccredited), hex"01", hex"02");
    }

    // ── propose ────────────────────────────────────────────────────────────

    function test_propose_succeedsForAccreditedMember() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", keccak256("ipfs://budget-2026"));
        assertEq(id, 0);
        assertEq(dapp.proposalCount(), 1);

        BoardroomGovernance.Proposal memory p = dapp.proposal(id);
        assertEq(p.proposer, alice);
        assertEq(uint8(p.outcome), uint8(BoardroomGovernance.Outcome.Pending));
    }

    function test_propose_revertsForUnboundWallet() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, propose_));
        dapp.propose("Rogue proposal", bytes32(0));
    }

    function test_propose_revertsWithoutAccreditationClaim() public {
        issuerAccredited.setValid(false);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, topicAccredited));
        dapp.propose("Needs accreditation", bytes32(0));
    }

    // ── vote ───────────────────────────────────────────────────────────────

    function test_vote_succeedsForKycMemberAndRecordsTally() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", bytes32(0));

        vm.prank(alice);
        dapp.vote(id, true);
        vm.prank(bob);
        dapp.vote(id, false);

        assertTrue(dapp.hasVoted(id, alice));
        assertTrue(dapp.hasVoted(id, bob));

        BoardroomGovernance.Proposal memory p = dapp.proposal(id);
        assertEq(p.votesFor, 1);
        assertEq(p.votesAgainst, 1);
    }

    function test_vote_revertsOnDoubleVote() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", bytes32(0));

        vm.prank(bob);
        dapp.vote(id, true);

        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(BoardroomGovernance.AlreadyVoted.selector, bob, id));
        dapp.vote(id, true);
    }

    function test_vote_revertsWithoutKycClaim() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", bytes32(0));

        issuerKyc.setValid(false);

        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, bob, topicKyc));
        dapp.vote(id, true);
    }

    function test_vote_revertsOnUnknownProposal() public {
        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(BoardroomGovernance.ProposalNotFound.selector, uint256(42)));
        dapp.vote(42, true);
    }

    // ── tally ──────────────────────────────────────────────────────────────

    function test_tally_computesOutcomeAndRevertsOnSecondCall() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", bytes32(0));
        vm.prank(alice);
        dapp.vote(id, true);
        vm.prank(bob);
        dapp.vote(id, true);

        vm.prank(bob); // tally not yet restricted — org grant alone suffices
        BoardroomGovernance.Outcome outcome = dapp.tally(id);
        assertEq(uint8(outcome), uint8(BoardroomGovernance.Outcome.Passed));

        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(BoardroomGovernance.ProposalAlreadyTallied.selector, id));
        dapp.tally(id);
    }

    /// @notice The role-restriction showcase: the org holds `boardroom.tally`, but once
    ///         the org admin (here modelled by the operator, since {OrgRegistry} treats
    ///         the operator as authoritative over every org) marks the permission
    ///         role-restricted, an unprivileged member is denied until the permission is
    ///         explicitly delegated to their role.
    function test_tally_respectsRoleRestriction() public {
        vm.prank(alice);
        uint256 id = dapp.propose("Approve 2026 budget", bytes32(0));
        vm.prank(alice);
        dapp.vote(id, true);

        vm.prank(operator);
        permissions.setRoleRestricted(address(orgId), tally_, true);

        // bob only holds MEMBER — no role carries the now-restricted permission.
        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, bob, tally_));
        dapp.tally(id);

        // Delegate to BOARD_SECRETARY; alice holds that role and may now tally.
        vm.prank(operator);
        permissions.grantToRole(address(orgId), BOARD_SECRETARY_ROLE, tally_);

        vm.prank(alice);
        BoardroomGovernance.Outcome outcome = dapp.tally(id);
        assertEq(uint8(outcome), uint8(BoardroomGovernance.Outcome.Passed));
    }

    // ── checkIn ────────────────────────────────────────────────────────────

    function test_checkIn_requiresActiveMember() public {
        vm.prank(alice);
        assertTrue(dapp.checkIn());

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.NotAnActiveMember.selector, mallory));
        dapp.checkIn();
    }
}
