// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/// @title IOrgRegistry
/// @notice Registry binding member wallets to organizations. An organization is keyed by its
///         ONCHAINID identity contract address; every participant wallet belongs to exactly
///         one organization per chain (SWIAT-style "every user belongs to a company").
interface IOrgRegistry {
    enum OrgStatus {
        None,
        Active,
        Suspended
    }

    // ── Events ────────────────────────────────────────────────────────────────

    event OrgRegistered(address indexed org, uint16 country);
    event OrgSuspended(address indexed org, string reason);
    event OrgReinstated(address indexed org);
    event MemberAdded(address indexed org, address indexed wallet, bytes32[] roles);
    event MemberRemoved(address indexed org, address indexed wallet);
    event MemberRolesSet(address indexed org, address indexed wallet, bytes32[] roles);

    // ── Org lifecycle (operator) ──────────────────────────────────────────────

    /// @notice Registers an organization by its ONCHAINID address.
    /// @param org     The organization's ONCHAINID identity contract.
    /// @param country ISO-3166 numeric country code of the legal entity.
    function registerOrg(address org, uint16 country) external;

    /// @notice Suspends an organization; its members lose all active-member semantics
    ///         and org admins can no longer self-administer memberships.
    function suspendOrg(address org, string calldata reason) external;

    /// @notice Reinstates a suspended organization.
    function reinstateOrg(address org) external;

    // ── Membership (operator or org admin) ────────────────────────────────────

    /// @notice Binds a wallet to an organization with an initial set of org-scoped roles.
    /// @param walletConsent EIP-712 {MEMBER_CONSENT_TYPEHASH} signature by `wallet`, required
    ///        only on the org-admin-direct path (pass empty bytes when relayed by the operator,
    ///        which is already gated off-chain before broadcast). See {OrgRegistry}'s NatSpec.
    function addMember(address org, address wallet, bytes32[] calldata roles, bytes calldata walletConsent) external;

    /// @notice Current consent nonce for `wallet` (see {addMember}'s `walletConsent`).
    function walletConsentNonce(address wallet) external view returns (uint256);

    /// @notice Unbinds a wallet from its organization; the wallet may be re-bound later.
    function removeMember(address org, address wallet) external;

    /// @notice Replaces the org-scoped roles of a bound wallet.
    function setMemberRoles(address org, address wallet, bytes32[] calldata roles) external;

    // ── Views ─────────────────────────────────────────────────────────────────

    /// @notice Returns the organization a wallet is bound to (address(0) if unbound).
    function orgOf(address wallet) external view returns (address);

    /// @notice Returns the registered country of an organization.
    function orgCountry(address org) external view returns (uint16);

    /// @notice Returns the lifecycle status of an organization.
    function orgStatus(address org) external view returns (OrgStatus);

    /// @notice Returns true if the organization is registered and not suspended.
    function isOrgActive(address org) external view returns (bool);

    /// @notice Returns true if the wallet is bound to an active organization.
    function isActiveMember(address wallet) external view returns (bool);

    /// @notice Returns true if the wallet is bound and holds the given org-scoped role.
    ///         Does not check org status — combine with {isActiveMember} for enforcement.
    function memberHasRole(address wallet, bytes32 role) external view returns (bool);

    /// @notice Returns the org-scoped roles of a wallet (empty if unbound).
    function memberRoles(address wallet) external view returns (bytes32[] memory);

    /// @notice Returns true if `caller` holds an ERC-734 MANAGEMENT key on the org's ONCHAINID.
    function isOrgAdmin(address org, address caller) external view returns (bool);
}
