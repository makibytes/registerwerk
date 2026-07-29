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
abstract contract RegisterwerkGated {
    /// @notice The Registerwerk permission oracle — the only ecosystem address a dApp stores.
    IPermissionOracle public immutable oracle;

    error PermissionDenied(address wallet, bytes32 permission);
    error ClaimMissing(address wallet, uint256 topic);
    error NotAnActiveMember(address wallet);

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
}
