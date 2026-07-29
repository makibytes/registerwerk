// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "./interfaces/IOrgRegistry.sol";
import "./interfaces/IPermissionRegistry.sol";

/// @title PermissionRegistry
/// @notice Grant storage of the ecosystem permission framework. Enforcement semantics
///         (org active, role restriction, member roles) are composed by the
///         {PermissionOracle}; this contract only stores and authorizes grants.
contract PermissionRegistry is AccessControl, IPermissionRegistry {
    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    IOrgRegistry public immutable orgRegistry;

    mapping(bytes32 => bool) private _defined;
    mapping(address => mapping(bytes32 => bool)) private _orgGrants;
    /// @dev Bumped on every org-level revoke. Role grants and the restriction flag record
    ///      the epoch they were set in (offset by +1 so 0 means "never set"); a mismatch
    ///      means the delegation predates the last revoke and is void. This guarantees a
    ///      re-granted org permission never silently resurrects old role delegations,
    ///      while keeping revokeFromOrg O(1).
    mapping(address => mapping(bytes32 => uint64)) private _orgGrantEpoch;
    mapping(address => mapping(bytes32 => mapping(bytes32 => uint64))) private _roleGrantEpoch;
    mapping(address => mapping(bytes32 => uint64)) private _roleRestrictedEpoch;

    constructor(address admin, IOrgRegistry orgRegistry_) {
        require(admin != address(0), "PermissionRegistry: zero admin address");
        require(address(orgRegistry_) != address(0), "PermissionRegistry: zero org registry");
        orgRegistry = orgRegistry_;
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    /// @inheritdoc IPermissionRegistry
    function definePermission(bytes32 id, string calldata code) external override onlyRole(OPERATOR_ROLE) {
        require(id == keccak256(bytes(code)), "PermissionRegistry: id is not keccak256(code)");
        require(!_defined[id], "PermissionRegistry: already defined");
        _defined[id] = true;
        emit PermissionDefined(id, code);
    }

    // ── Operator tier ─────────────────────────────────────────────────────────

    /// @inheritdoc IPermissionRegistry
    function grantToOrg(address org, bytes32 permission) external override onlyRole(OPERATOR_ROLE) {
        require(orgRegistry.orgStatus(org) != IOrgRegistry.OrgStatus.None, "PermissionRegistry: org not registered");
        _orgGrants[org][permission] = true;
        emit OrgPermissionGranted(org, permission);
    }

    /// @inheritdoc IPermissionRegistry
    /// @dev Also voids every role delegation and the restriction flag for this permission
    ///      by bumping the epoch — they must be re-established after a re-grant.
    function revokeFromOrg(address org, bytes32 permission) external override onlyRole(OPERATOR_ROLE) {
        _orgGrants[org][permission] = false;
        unchecked {
            _orgGrantEpoch[org][permission] += 1;
        }
        emit OrgPermissionRevoked(org, permission);
    }

    // ── Org-admin tier ────────────────────────────────────────────────────────

    /// @inheritdoc IPermissionRegistry
    function grantToRole(address org, bytes32 role, bytes32 permission) external override {
        _checkOrgAuthority(org);
        require(_orgGrants[org][permission], "PermissionRegistry: org does not hold permission");
        _roleGrantEpoch[org][role][permission] = _orgGrantEpoch[org][permission] + 1;
        emit RolePermissionGranted(org, role, permission);
    }

    /// @inheritdoc IPermissionRegistry
    function revokeFromRole(address org, bytes32 role, bytes32 permission) external override {
        _checkOrgAuthority(org);
        _roleGrantEpoch[org][role][permission] = 0;
        emit RolePermissionRevoked(org, role, permission);
    }

    /// @inheritdoc IPermissionRegistry
    function setRoleRestricted(address org, bytes32 permission, bool restricted) external override {
        _checkOrgAuthority(org);
        require(
            !restricted || _orgGrants[org][permission],
            "PermissionRegistry: org does not hold permission"
        );
        _roleRestrictedEpoch[org][permission] = restricted ? _orgGrantEpoch[org][permission] + 1 : 0;
        emit RoleRestrictionSet(org, permission, restricted);
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    /// @inheritdoc IPermissionRegistry
    function isDefined(bytes32 permission) external view override returns (bool) {
        return _defined[permission];
    }

    /// @inheritdoc IPermissionRegistry
    function orgGranted(address org, bytes32 permission) external view override returns (bool) {
        return _orgGrants[org][permission];
    }

    /// @inheritdoc IPermissionRegistry
    function roleGranted(address org, bytes32 role, bytes32 permission) external view override returns (bool) {
        uint64 epoch = _roleGrantEpoch[org][role][permission];
        return epoch != 0 && epoch == _orgGrantEpoch[org][permission] + 1;
    }

    /// @inheritdoc IPermissionRegistry
    function isRoleRestricted(address org, bytes32 permission) external view override returns (bool) {
        uint64 epoch = _roleRestrictedEpoch[org][permission];
        return epoch != 0 && epoch == _orgGrantEpoch[org][permission] + 1;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /// @dev Operator may manage any registered org's delegations; an org admin only their
    ///      own org's and only while it is Active (suspension freezes self-administration).
    function _checkOrgAuthority(address org) internal view {
        if (hasRole(OPERATOR_ROLE, msg.sender)) {
            require(orgRegistry.orgStatus(org) != IOrgRegistry.OrgStatus.None, "PermissionRegistry: org not registered");
            return;
        }
        require(orgRegistry.isOrgActive(org), "PermissionRegistry: org not active");
        require(orgRegistry.isOrgAdmin(org, msg.sender), "PermissionRegistry: not operator or org admin");
    }
}
