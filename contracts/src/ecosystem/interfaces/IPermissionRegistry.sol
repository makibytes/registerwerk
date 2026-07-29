// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/// @title IPermissionRegistry
/// @notice Two-tier permission grants for ecosystem dApps. The registry operator grants
///         permissions to organizations; org admins may delegate a granted permission to
///         org-scoped member roles and mark it role-restricted.
///
///         Permission id convention: keccak256(bytes("<dappSlug>.<action>")) for dApp
///         permissions, keccak256(bytes("registerwerk.<action>")) for platform permissions.
interface IPermissionRegistry {
    // ── Events ────────────────────────────────────────────────────────────────

    event PermissionDefined(bytes32 indexed id, string code);
    event OrgPermissionGranted(address indexed org, bytes32 indexed permission);
    event OrgPermissionRevoked(address indexed org, bytes32 indexed permission);
    event RolePermissionGranted(address indexed org, bytes32 indexed role, bytes32 indexed permission);
    event RolePermissionRevoked(address indexed org, bytes32 indexed role, bytes32 indexed permission);
    event RoleRestrictionSet(address indexed org, bytes32 indexed permission, bool restricted);

    // ── Definitions (operator, indexer metadata) ──────────────────────────────

    /// @notice Publishes a permission definition. The id must equal keccak256(bytes(code));
    ///         definitions are metadata for indexers and UIs — grants do not require one.
    function definePermission(bytes32 id, string calldata code) external;

    // ── Operator tier ─────────────────────────────────────────────────────────

    function grantToOrg(address org, bytes32 permission) external;

    function revokeFromOrg(address org, bytes32 permission) external;

    // ── Org-admin tier (delegation) ───────────────────────────────────────────

    /// @notice Delegates an org-granted permission to an org-scoped member role.
    function grantToRole(address org, bytes32 role, bytes32 permission) external;

    function revokeFromRole(address org, bytes32 role, bytes32 permission) external;

    /// @notice When restricted, holding the org grant is not enough — the member wallet
    ///         must additionally hold a role that carries the permission.
    function setRoleRestricted(address org, bytes32 permission, bool restricted) external;

    // ── Views ─────────────────────────────────────────────────────────────────

    function isDefined(bytes32 permission) external view returns (bool);

    function orgGranted(address org, bytes32 permission) external view returns (bool);

    function roleGranted(address org, bytes32 role, bytes32 permission) external view returns (bool);

    function isRoleRestricted(address org, bytes32 permission) external view returns (bool);
}
