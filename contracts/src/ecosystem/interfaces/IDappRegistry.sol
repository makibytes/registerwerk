// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

/// @title IDappRegistry
/// @notice Onchain registry of marketplace-approved dApps. Registration is the outcome of
///         the operator's review workflow: the manifest hash anchors the exact reviewed
///         manifest (keccak256 of its raw bytes), enabling anyone to verify integrity.
interface IDappRegistry {
    enum DappStatus {
        None,
        Active,
        Suspended,
        Deprecated
    }

    struct Dapp {
        address publisherOrg;
        bytes32 manifestHash;
        string version;
        DappStatus status;
        bytes32[] requiredPermissions;
        uint256[] requiredClaimTopics;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    event DappRegistered(bytes32 indexed dappId, address indexed publisherOrg, bytes32 manifestHash, string version);
    event ManifestUpdated(bytes32 indexed dappId, bytes32 oldHash, bytes32 newHash, string version);
    event DappStatusChanged(bytes32 indexed dappId, DappStatus status);
    event InstanceAttested(bytes32 indexed dappId, address indexed instance);
    event InstanceRevoked(bytes32 indexed dappId, address indexed instance);

    // ── Registration (operator = review approval outcome) ────────────────────

    /// @notice Registers an approved dApp. dappId convention: keccak256(bytes(slug)).
    function registerDapp(
        bytes32 dappId,
        address publisherOrg,
        bytes32 manifestHash,
        string calldata version,
        bytes32[] calldata requiredPermissions,
        uint256[] calldata requiredClaimTopics
    ) external;

    /// @notice Anchors a newly approved manifest version. History lives in events.
    function updateManifest(bytes32 dappId, bytes32 newHash, string calldata version) external;

    function setStatus(bytes32 dappId, DappStatus status) external;

    // ── Instance attestation (opt-in composition layer) ──────────────────────

    /// @notice The publisher's org admin (or the operator) attests a deployed contract
    ///         instance of the dApp, so tokens/dApps that opt in can require
    ///         {isApprovedInstance} on their callers.
    function attestInstance(bytes32 dappId, address instance) external;

    function revokeInstance(address instance) external;

    // ── Views ─────────────────────────────────────────────────────────────────

    function isApproved(bytes32 dappId) external view returns (bool);

    function getDapp(bytes32 dappId) external view returns (Dapp memory);

    /// @notice The dApp an attested instance belongs to (bytes32(0) if unattested).
    function dappOf(address instance) external view returns (bytes32);

    /// @notice True if the instance is attested and its dApp is Active.
    function isApprovedInstance(address instance) external view returns (bool);
}
