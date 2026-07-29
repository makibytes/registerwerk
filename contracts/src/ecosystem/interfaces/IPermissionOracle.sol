// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/// @title IPermissionOracle
/// @notice The single stable query facade of the Registerwerk ecosystem. Customer dApps
///         store the oracle address (immutably) and gate their actions through these
///         views; the operator can repoint the oracle's underlying registries without
///         breaking deployed dApps.
interface IPermissionOracle {
    /// @notice The organization (ONCHAINID address) a wallet is bound to; address(0) if unbound.
    function orgOf(address wallet) external view returns (address);

    /// @notice True if the wallet is bound to an active org that holds `permission` —
    ///         and, when the permission is role-restricted for the org, the wallet
    ///         additionally holds a member role carrying the permission.
    function hasPermission(address wallet, bytes32 permission) external view returns (bool);

    /// @notice True if the wallet is bound to an active (non-suspended) organization.
    function isActiveMember(address wallet) external view returns (bool);

    /// @notice True if the wallet is an active member holding the org-scoped role.
    function hasRole(address wallet, bytes32 role) external view returns (bool);

    /// @notice True if the wallet's org ONCHAINID carries a valid claim of the given topic,
    ///         signed by an issuer trusted for that topic in the ecosystem TIR.
    function hasClaimTopic(address wallet, uint256 topic) external view returns (bool);

    /// @notice Combined check: {hasPermission} and every claim topic in `topics`.
    function check(address wallet, bytes32 permission, uint256[] calldata topics) external view returns (bool);

    /// @notice True if `dappInstance` is attested in the DappRegistry as an instance of an
    ///         approved marketplace dApp. Opt-in composition — not folded into
    ///         {hasPermission}, since self-hosted deployments control their own callers.
    function isApprovedInstance(address dappInstance) external view returns (bool);
}
