// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "./interfaces/IPermissionOracle.sol";

/// @title RegisterwerkGated
/// @notice SDK base contract for ecosystem dApps. Inherit it, pass the PermissionOracle
///         address in your constructor, and gate actions with the modifiers:
///
/// ```solidity
/// contract LoanDesk is RegisterwerkGated {
///     bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");
///
///     constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}
///
///     function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
///         // caller's wallet belongs to an active org holding "loandesk.open",
///         // and the org's ONCHAINID carries a valid KYC claim (topic 1)
///     }
/// }
/// ```
///
///         Permissions are org-wide: a grant of "loandesk.open" says *what* an org may do on
///         every instance of the dApp, not *whose* instance it is. A value-holding instance
///         therefore binds itself to its operating org and gates privileged functions with
///         {requiresOrgPermission}:
///
/// ```solidity
/// address public immutable operatorOrg;
///
/// constructor(IPermissionOracle oracle_, address operatorOrg_) RegisterwerkGated(oracle_) {
///     _requireOrg(operatorOrg_);
///     operatorOrg = operatorOrg_;
/// }
///
/// function sweep() external requiresOrgPermission(operatorOrg, SWEEP) { ... }
/// ```
abstract contract RegisterwerkGated {
    /// @notice The Registerwerk permission oracle — the only ecosystem address a dApp stores.
    IPermissionOracle public immutable oracle;

    error PermissionDenied(address wallet, bytes32 permission);
    error ClaimMissing(address wallet, uint256 topic);
    error NotAnActiveMember(address wallet);
    /// @notice The caller's wallet is not bound to the org that operates this instance.
    error WrongOperatingOrg(address wallet, address expectedOrg);
    /// @notice An instance was configured with the zero address as its operating org.
    error ZeroOperatingOrg();

    constructor(IPermissionOracle oracle_) {
        require(address(oracle_) != address(0), "RegisterwerkGated: zero oracle address");
        oracle = oracle_;
    }

    /// @notice Requires the caller's wallet to hold the permission via its organization.
    modifier requiresPermission(bytes32 permission) {
        if (!oracle.hasPermission(msg.sender, permission)) {
            revert PermissionDenied(msg.sender, permission);
        }
        _;
    }

    /// @notice Instance binding: requires the caller's wallet to be bound to `org` (the org
    ///         operating this instance) **and** to hold `permission` via that org. Reverts
    ///         {WrongOperatingOrg} before the permission is looked at, so a same-slug grant
    ///         held by another org never reaches a foreign instance.
    modifier requiresOrgPermission(address org, bytes32 permission) {
        _checkOrgPermission(msg.sender, org, permission);
        _;
    }

    /// @notice Requires the caller's org ONCHAINID to carry a valid claim of the topic.
    modifier requiresClaim(uint256 topic) {
        if (!oracle.hasClaimTopic(msg.sender, topic)) {
            revert ClaimMissing(msg.sender, topic);
        }
        _;
    }

    /// @notice Requires the caller's wallet to be bound to an active organization.
    modifier requiresActiveMember() {
        if (!oracle.isActiveMember(msg.sender)) {
            revert NotAnActiveMember(msg.sender);
        }
        _;
    }

    /// @dev Body of {requiresOrgPermission}, for instances whose operating org is resolved
    ///      inside the function (e.g. per-asset). `org == address(0)` never matches — an
    ///      unset binding fails closed rather than matching unbound wallets.
    function _checkOrgPermission(address wallet, address org, bytes32 permission) internal view {
        if (org == address(0) || oracle.orgOf(wallet) != org) {
            revert WrongOperatingOrg(wallet, org);
        }
        if (!oracle.hasPermission(wallet, permission)) {
            revert PermissionDenied(wallet, permission);
        }
    }

    /// @dev Constructor/setter guard for an operating-org binding.
    function _requireOrg(address org) internal pure {
        if (org == address(0)) revert ZeroOperatingOrg();
    }
}
