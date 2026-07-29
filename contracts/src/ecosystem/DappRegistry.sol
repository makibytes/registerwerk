// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "./interfaces/IDappRegistry.sol";
import "./interfaces/IOrgRegistry.sol";

/// @title DappRegistry
/// @notice Anchors the marketplace review workflow onchain: approved dApp manifests
///         (by keccak256 hash), their lifecycle status and — as an opt-in layer —
///         attested contract instances of each dApp.
contract DappRegistry is AccessControl, IDappRegistry {
    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    IOrgRegistry public immutable orgRegistry;

    mapping(bytes32 => Dapp) private _dapps;
    mapping(address => bytes32) private _dappOfInstance;

    constructor(address admin, IOrgRegistry orgRegistry_) {
        require(admin != address(0), "DappRegistry: zero admin address");
        require(address(orgRegistry_) != address(0), "DappRegistry: zero org registry");
        orgRegistry = orgRegistry_;
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /// @inheritdoc IDappRegistry
    function registerDapp(
        bytes32 dappId,
        address publisherOrg,
        bytes32 manifestHash,
        string calldata version,
        bytes32[] calldata requiredPermissions,
        uint256[] calldata requiredClaimTopics
    ) external override onlyRole(OPERATOR_ROLE) {
        require(dappId != bytes32(0), "DappRegistry: zero dapp id");
        require(manifestHash != bytes32(0), "DappRegistry: zero manifest hash");
        require(_dapps[dappId].status == DappStatus.None, "DappRegistry: dapp already registered");
        require(
            orgRegistry.orgStatus(publisherOrg) != IOrgRegistry.OrgStatus.None,
            "DappRegistry: publisher org not registered"
        );

        _dapps[dappId] = Dapp({
            publisherOrg: publisherOrg,
            manifestHash: manifestHash,
            version: version,
            status: DappStatus.Active,
            requiredPermissions: requiredPermissions,
            requiredClaimTopics: requiredClaimTopics
        });
        emit DappRegistered(dappId, publisherOrg, manifestHash, version);
    }

    /// @inheritdoc IDappRegistry
    function updateManifest(bytes32 dappId, bytes32 newHash, string calldata version)
        external
        override
        onlyRole(OPERATOR_ROLE)
    {
        Dapp storage dapp = _requireRegistered(dappId);
        require(newHash != bytes32(0), "DappRegistry: zero manifest hash");
        emit ManifestUpdated(dappId, dapp.manifestHash, newHash, version);
        dapp.manifestHash = newHash;
        dapp.version = version;
    }

    /// @inheritdoc IDappRegistry
    function setStatus(bytes32 dappId, DappStatus status) external override onlyRole(OPERATOR_ROLE) {
        require(status != DappStatus.None, "DappRegistry: invalid status");
        Dapp storage dapp = _requireRegistered(dappId);
        dapp.status = status;
        emit DappStatusChanged(dappId, status);
    }

    // ── Instance attestation ──────────────────────────────────────────────────

    /// @inheritdoc IDappRegistry
    function attestInstance(bytes32 dappId, address instance) external override {
        Dapp storage dapp = _requireRegistered(dappId);
        require(dapp.status == DappStatus.Active, "DappRegistry: dapp not active");
        require(instance != address(0), "DappRegistry: zero instance address");
        require(_dappOfInstance[instance] == bytes32(0), "DappRegistry: instance already attested");
        require(
            hasRole(OPERATOR_ROLE, msg.sender) || orgRegistry.isOrgAdmin(dapp.publisherOrg, msg.sender),
            "DappRegistry: not operator or publisher admin"
        );

        _dappOfInstance[instance] = dappId;
        emit InstanceAttested(dappId, instance);
    }

    /// @inheritdoc IDappRegistry
    function revokeInstance(address instance) external override onlyRole(OPERATOR_ROLE) {
        bytes32 dappId = _dappOfInstance[instance];
        require(dappId != bytes32(0), "DappRegistry: instance not attested");
        delete _dappOfInstance[instance];
        emit InstanceRevoked(dappId, instance);
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    /// @inheritdoc IDappRegistry
    function isApproved(bytes32 dappId) public view override returns (bool) {
        return _dapps[dappId].status == DappStatus.Active;
    }

    /// @inheritdoc IDappRegistry
    function getDapp(bytes32 dappId) external view override returns (Dapp memory) {
        return _dapps[dappId];
    }

    /// @inheritdoc IDappRegistry
    function dappOf(address instance) external view override returns (bytes32) {
        return _dappOfInstance[instance];
    }

    /// @inheritdoc IDappRegistry
    function isApprovedInstance(address instance) external view override returns (bool) {
        bytes32 dappId = _dappOfInstance[instance];
        return dappId != bytes32(0) && isApproved(dappId);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    function _requireRegistered(bytes32 dappId) internal view returns (Dapp storage dapp) {
        dapp = _dapps[dappId];
        require(dapp.status != DappStatus.None, "DappRegistry: dapp not registered");
    }
}
