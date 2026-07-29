// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "@openzeppelin/contracts/utils/cryptography/EIP712.sol";
import "@openzeppelin/contracts/utils/cryptography/SignatureChecker.sol";
import "./interfaces/IIdentity.sol";
import "./interfaces/IOrgRegistry.sol";

/// @title OrgRegistry
/// @notice Binds member wallets to organizations. An organization is keyed by its ONCHAINID
///         identity contract (deployed via the registry operator's IdFactory), so the org
///         anchor is the same identity that carries the entity's KYC/AML claims.
///
/// @dev Authorization is two-tier:
///      - The registry operator (OPERATOR_ROLE) manages org lifecycle and may administer
///        any registered org's memberships (custodial default — the backend relays). The
///        backend only ever broadcasts `addMember` for a wallet that already completed its
///        own off-chain nonce-challenge + personal_sign proof of control
///        (`MemberWalletService.bindWallet`), so no additional onchain proof is
///        required for this path — it is already gated before the transaction is ever sent.
///      - An "org admin" — a wallet holding an ERC-734 MANAGEMENT key (purpose 1) on the
///        org's ONCHAINID — may self-administer memberships while the org is Active. This
///        path has no such off-chain gate, so `addMember` additionally requires an EIP-712
///        signature FROM the wallet being bound, proving it actually consents to joining
///        this org — otherwise an org admin could bind an arbitrary third-party address
///        (e.g. to fraudulently grant it dApp permissions via {PermissionOracle}, which reads
///        `orgOf`/`memberRoles` directly from this contract) with no proof the wallet's real
///        owner ever agreed.
///
///      A wallet belongs to at most one organization per chain, keeping `orgOf`
///      unambiguous for permission checks. Roles are org-scoped opaque hashes
///      (e.g. keccak256("TRADER")) with meaning assigned off-chain and by dApps.
contract OrgRegistry is AccessControl, EIP712, IOrgRegistry {
    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    /// @dev ERC-734 key purpose that designates an org admin on the org's ONCHAINID.
    uint256 public constant MANAGEMENT_KEY_PURPOSE = 1;

    /// @notice Upper bound on roles per member, keeping role scans cheap.
    uint256 public constant MAX_MEMBER_ROLES = 16;

    /// @notice EIP-712 typehash for the wallet-consent struct required on the org-admin-direct
    ///         `addMember` path. `nonce` is this contract's own per-wallet counter (see
    ///         {walletConsentNonce}), incremented on use — not the backend's separate,
    ///         off-chain challenge nonce, so this proof is self-contained and independently
    ///         verifiable without trusting any off-chain state.
    bytes32 public constant MEMBER_CONSENT_TYPEHASH =
        keccak256("MemberConsent(address org,address wallet,uint256 nonce)");

    struct Org {
        OrgStatus status;
        uint16 country;
        uint64 memberCount;
    }

    mapping(address => Org) private _orgs;
    mapping(address => address) private _orgOf;
    mapping(address => bytes32[]) private _rolesOf;
    mapping(address => uint256) private _walletConsentNonce;

    constructor(address admin) EIP712("Registerwerk OrgRegistry", "1") {
        require(admin != address(0), "OrgRegistry: zero admin address");
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
    }

    // ── Org lifecycle (operator) ──────────────────────────────────────────────

    /// @inheritdoc IOrgRegistry
    function registerOrg(address org, uint16 country) external override onlyRole(OPERATOR_ROLE) {
        require(org != address(0), "OrgRegistry: zero org address");
        require(_orgs[org].status == OrgStatus.None, "OrgRegistry: org already registered");
        _orgs[org] = Org({status: OrgStatus.Active, country: country, memberCount: 0});
        emit OrgRegistered(org, country);
    }

    /// @inheritdoc IOrgRegistry
    function suspendOrg(address org, string calldata reason) external override onlyRole(OPERATOR_ROLE) {
        require(_orgs[org].status == OrgStatus.Active, "OrgRegistry: org not active");
        _orgs[org].status = OrgStatus.Suspended;
        emit OrgSuspended(org, reason);
    }

    /// @inheritdoc IOrgRegistry
    function reinstateOrg(address org) external override onlyRole(OPERATOR_ROLE) {
        require(_orgs[org].status == OrgStatus.Suspended, "OrgRegistry: org not suspended");
        _orgs[org].status = OrgStatus.Active;
        emit OrgReinstated(org);
    }

    // ── Membership (operator or org admin) ────────────────────────────────────

    /// @inheritdoc IOrgRegistry
    /// @param walletConsent EIP-712 signature of {MEMBER_CONSENT_TYPEHASH} by `wallet` itself,
    ///        over `(org, wallet, walletConsentNonce(wallet))`. Required only when the caller is
    ///        an org admin (not OPERATOR_ROLE) — see the contract-level NatSpec for why the
    ///        operator-relayed path needs none. Pass empty bytes for operator-relayed calls.
    function addMember(address org, address wallet, bytes32[] calldata roles, bytes calldata walletConsent)
        external
        override
    {
        _checkOrgAuthority(org);
        require(wallet != address(0), "OrgRegistry: zero wallet address");
        require(_orgOf[wallet] == address(0), "OrgRegistry: wallet already bound");
        require(roles.length <= MAX_MEMBER_ROLES, "OrgRegistry: too many roles");

        if (!hasRole(OPERATOR_ROLE, msg.sender)) {
            uint256 nonce = _walletConsentNonce[wallet];
            bytes32 structHash = keccak256(abi.encode(MEMBER_CONSENT_TYPEHASH, org, wallet, nonce));
            bytes32 digest = _hashTypedDataV4(structHash);
            require(
                SignatureChecker.isValidSignatureNow(wallet, digest, walletConsent),
                "OrgRegistry: invalid wallet consent signature"
            );
            _walletConsentNonce[wallet] = nonce + 1;
        }

        _orgOf[wallet] = org;
        _rolesOf[wallet] = roles;
        _orgs[org].memberCount += 1;
        emit MemberAdded(org, wallet, roles);
    }

    /// @notice Current consent nonce for `wallet` — callers must sign over this value for their
    ///         next {addMember} consent to be accepted; it increments each time consent is used,
    ///         preventing replay of an old signature (e.g. after removal and an attempted
    ///         re-bind by a different org admin without the wallet's renewed consent).
    function walletConsentNonce(address wallet) external view returns (uint256) {
        return _walletConsentNonce[wallet];
    }

    /// @inheritdoc IOrgRegistry
    function removeMember(address org, address wallet) external override {
        _checkOrgAuthority(org);
        require(_orgOf[wallet] == org, "OrgRegistry: wallet not bound to org");

        delete _orgOf[wallet];
        delete _rolesOf[wallet];
        _orgs[org].memberCount -= 1;
        emit MemberRemoved(org, wallet);
    }

    /// @inheritdoc IOrgRegistry
    function setMemberRoles(address org, address wallet, bytes32[] calldata roles) external override {
        _checkOrgAuthority(org);
        require(_orgOf[wallet] == org, "OrgRegistry: wallet not bound to org");
        require(roles.length <= MAX_MEMBER_ROLES, "OrgRegistry: too many roles");

        _rolesOf[wallet] = roles;
        emit MemberRolesSet(org, wallet, roles);
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    /// @inheritdoc IOrgRegistry
    function orgOf(address wallet) external view override returns (address) {
        return _orgOf[wallet];
    }

    /// @inheritdoc IOrgRegistry
    function orgCountry(address org) external view override returns (uint16) {
        return _orgs[org].country;
    }

    /// @inheritdoc IOrgRegistry
    function orgStatus(address org) external view override returns (OrgStatus) {
        return _orgs[org].status;
    }

    /// @inheritdoc IOrgRegistry
    function isOrgActive(address org) public view override returns (bool) {
        return _orgs[org].status == OrgStatus.Active;
    }

    /// @inheritdoc IOrgRegistry
    function isActiveMember(address wallet) external view override returns (bool) {
        address org = _orgOf[wallet];
        return org != address(0) && isOrgActive(org);
    }

    /// @inheritdoc IOrgRegistry
    function memberHasRole(address wallet, bytes32 role) external view override returns (bool) {
        bytes32[] storage roles = _rolesOf[wallet];
        for (uint256 i = 0; i < roles.length; i++) {
            if (roles[i] == role) {
                return true;
            }
        }
        return false;
    }

    /// @inheritdoc IOrgRegistry
    function memberRoles(address wallet) external view override returns (bytes32[] memory) {
        return _rolesOf[wallet];
    }

    /// @inheritdoc IOrgRegistry
    function isOrgAdmin(address org, address caller) public view override returns (bool) {
        if (org.code.length == 0) {
            return false;
        }
        try IIdentity(org).keyHasPurpose(keccak256(abi.encode(caller)), MANAGEMENT_KEY_PURPOSE) returns (bool held) {
            return held;
        } catch {
            return false;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /// @dev Operators may administer any registered org (including suspended ones, for
    ///      cleanup); org admins only their own org and only while it is Active —
    ///      suspension freezes self-administration by design.
    function _checkOrgAuthority(address org) internal view {
        if (hasRole(OPERATOR_ROLE, msg.sender)) {
            require(_orgs[org].status != OrgStatus.None, "OrgRegistry: org not registered");
            return;
        }
        require(_orgs[org].status == OrgStatus.Active, "OrgRegistry: org not active");
        require(isOrgAdmin(org, msg.sender), "OrgRegistry: not operator or org admin");
    }
}
