// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";

/// @title BoardroomGovernance
/// @notice Reference marketplace dApp: board-level governance and voting for a
///         tokenised entity, written to be the living documentation for the
///         Registerwerk ONCHAINID permission-management framework.
///
/// @dev Nothing in this contract is EVM-token-specific — {governanceToken} is stored
///      purely as an informational pointer back to the entity whose board this instance
///      governs (typically an ERC-3643 equity token). Every access-control decision runs
///      through {RegisterwerkGated}, i.e. through the org's ONCHAINID and the ecosystem
///      {PermissionOracle} — this contract never touches ONCHAINID or the permission
///      registries directly.
///
///      Permission surface (namespace "boardroom."), matching the manifest shipped in
///      `backend/src/main/resources/demo/dapps/boardroom.manifest.json`:
///        - boardroom.propose : open a new board resolution        (+ Accreditation claim)
///        - boardroom.vote    : cast a vote on an open resolution  (+ KYC claim)
///        - boardroom.tally   : close voting, record the outcome   (no claim requirement)
///
///      `tally` is the intended role-restriction showcase: the operator grants the org
///      `boardroom.tally` (`PermissionRegistry.grantToOrg`), the org admin then calls
///      `PermissionRegistry.setRoleRestricted(org, TALLY, true)` and delegates the
///      permission only to wallets carrying the `BOARD_SECRETARY` member role
///      (`PermissionRegistry.grantToRole`). From that point on, every other member of the
///      org — even though the org itself holds the permission — is denied until the org
///      admin explicitly delegates the role. This demonstrates that org admins, not just
///      the Registerwerk operator, control who inside their organization may exercise a
///      permission the operator granted to the org as a whole.
///
///      `checkIn` demonstrates the bare {requiresActiveMember} modifier in isolation
///      (no permission or claim needed) — e.g. for a quorum roll call.
contract BoardroomGovernance is RegisterwerkGated {
    bytes32 public constant PROPOSE = keccak256("boardroom.propose");
    bytes32 public constant VOTE = keccak256("boardroom.vote");
    bytes32 public constant TALLY = keccak256("boardroom.tally");

    uint256 public constant TOPIC_KYC = 1;
    uint256 public constant TOPIC_ACCREDITED = 3;

    enum Outcome {
        Pending,
        Passed,
        Rejected
    }

    struct Proposal {
        address proposer;
        string title;
        bytes32 descriptionHash;
        uint64 createdAt;
        uint256 votesFor;
        uint256 votesAgainst;
        Outcome outcome;
    }

    /// @notice Informational pointer to the entity's cap-table token (e.g. an ERC-3643
    ///         equity token). Not read by this contract — governance here is
    ///         one-member-one-vote, not balance-weighted, so no token calls are made.
    address public immutable governanceToken;

    Proposal[] private _proposals;
    mapping(uint256 => mapping(address => bool)) private _hasVoted;

    event ProposalCreated(
        uint256 indexed proposalId, address indexed proposer, string title, bytes32 descriptionHash
    );
    event VoteCast(uint256 indexed proposalId, address indexed voter, bool support);
    event ProposalTallied(
        uint256 indexed proposalId, address indexed talliedBy, Outcome outcome, uint256 votesFor, uint256 votesAgainst
    );

    error ProposalNotFound(uint256 proposalId);
    error ProposalAlreadyTallied(uint256 proposalId);
    error AlreadyVoted(address voter, uint256 proposalId);

    constructor(IPermissionOracle oracle_, address governanceToken_) RegisterwerkGated(oracle_) {
        governanceToken = governanceToken_;
    }

    /// @notice Open a new board resolution. Restricted to accredited proposers holding
    ///         `boardroom.propose`.
    function propose(string calldata title, bytes32 descriptionHash)
        external
        requiresPermission(PROPOSE)
        requiresClaim(TOPIC_ACCREDITED)
        returns (uint256 proposalId)
    {
        proposalId = _proposals.length;
        _proposals.push(
            Proposal({
                proposer: msg.sender,
                title: title,
                descriptionHash: descriptionHash,
                createdAt: uint64(block.timestamp),
                votesFor: 0,
                votesAgainst: 0,
                outcome: Outcome.Pending
            })
        );
        emit ProposalCreated(proposalId, msg.sender, title, descriptionHash);
    }

    /// @notice Cast one vote on an open resolution. Any KYC'd wallet holding the
    ///         org-granted `boardroom.vote` permission may vote once per proposal.
    function vote(uint256 proposalId, bool support) external requiresPermission(VOTE) requiresClaim(TOPIC_KYC) {
        Proposal storage p = _requireProposal(proposalId);
        if (p.outcome != Outcome.Pending) revert ProposalAlreadyTallied(proposalId);
        if (_hasVoted[proposalId][msg.sender]) revert AlreadyVoted(msg.sender, proposalId);

        _hasVoted[proposalId][msg.sender] = true;
        if (support) {
            p.votesFor += 1;
        } else {
            p.votesAgainst += 1;
        }
        emit VoteCast(proposalId, msg.sender, support);
    }

    /// @notice Close voting and record the outcome. Gated solely by `boardroom.tally` —
    ///         see the contract-level NatSpec for why this is the role-restriction demo.
    function tally(uint256 proposalId) external requiresPermission(TALLY) returns (Outcome outcome) {
        Proposal storage p = _requireProposal(proposalId);
        if (p.outcome != Outcome.Pending) revert ProposalAlreadyTallied(proposalId);

        outcome = p.votesFor > p.votesAgainst ? Outcome.Passed : Outcome.Rejected;
        p.outcome = outcome;
        emit ProposalTallied(proposalId, msg.sender, outcome, p.votesFor, p.votesAgainst);
    }

    /// @notice Attendance check-in — demonstrates the bare {requiresActiveMember}
    ///         modifier, with no permission grant or claim required.
    function checkIn() external requiresActiveMember returns (bool) {
        return true;
    }

    // ── Views ──────────────────────────────────────────────────────────────

    function proposalCount() external view returns (uint256) {
        return _proposals.length;
    }

    function proposal(uint256 proposalId) external view returns (Proposal memory) {
        return _requireProposal(proposalId);
    }

    function hasVoted(uint256 proposalId, address voter) external view returns (bool) {
        return _hasVoted[proposalId][voter];
    }

    function _requireProposal(uint256 proposalId) private view returns (Proposal storage) {
        if (proposalId >= _proposals.length) revert ProposalNotFound(proposalId);
        return _proposals[proposalId];
    }
}
