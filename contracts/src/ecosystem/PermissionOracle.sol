// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "./interfaces/IClaimIssuer.sol";
import "./interfaces/IIdentity.sol";
import "./interfaces/IOrgRegistry.sol";
import "./interfaces/IPermissionOracle.sol";
import "./interfaces/IPermissionRegistry.sol";
import "./EcosystemTrustedIssuersRegistry.sol";

/// @dev Minimal view of DappRegistry, so the oracle compiles before it exists.
interface IDappRegistryView {
    function isApprovedInstance(address dappInstance) external view returns (bool);
}

/// @title PermissionOracle
/// @notice Composes OrgRegistry, PermissionRegistry, DappRegistry and the ecosystem
///         TrustedIssuersRegistry into the one query surface customer dApps rely on.
///
/// @dev All checks fail closed: unset components, reverting external calls and unknown
///      wallets always return false. The operator repoints components via the setters;
///      dApps keep the oracle address immutable (see {RegisterwerkGated}).
contract PermissionOracle is AccessControl, IPermissionOracle {
    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    IOrgRegistry public orgRegistry;
    IPermissionRegistry public permissionRegistry;
    EcosystemTrustedIssuersRegistry public trustedIssuersRegistry;
    IDappRegistryView public dappRegistry;

    event ComponentUpdated(bytes32 indexed name, address oldAddress, address newAddress);

    constructor(
        address admin,
        IOrgRegistry orgRegistry_,
        IPermissionRegistry permissionRegistry_,
        EcosystemTrustedIssuersRegistry trustedIssuersRegistry_
    ) {
        require(admin != address(0), "PermissionOracle: zero admin address");
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
        orgRegistry = orgRegistry_;
        permissionRegistry = permissionRegistry_;
        trustedIssuersRegistry = trustedIssuersRegistry_;
    }

    // ── Component pointers (operator) ─────────────────────────────────────────

    function setOrgRegistry(IOrgRegistry newRegistry) external onlyRole(OPERATOR_ROLE) {
        emit ComponentUpdated("ORG_REGISTRY", address(orgRegistry), address(newRegistry));
        orgRegistry = newRegistry;
    }

    function setPermissionRegistry(IPermissionRegistry newRegistry) external onlyRole(OPERATOR_ROLE) {
        emit ComponentUpdated("PERMISSION_REGISTRY", address(permissionRegistry), address(newRegistry));
        permissionRegistry = newRegistry;
    }

    function setTrustedIssuersRegistry(EcosystemTrustedIssuersRegistry newRegistry)
        external
        onlyRole(OPERATOR_ROLE)
    {
        emit ComponentUpdated("TRUSTED_ISSUERS_REGISTRY", address(trustedIssuersRegistry), address(newRegistry));
        trustedIssuersRegistry = newRegistry;
    }

    function setDappRegistry(IDappRegistryView newRegistry) external onlyRole(OPERATOR_ROLE) {
        emit ComponentUpdated("DAPP_REGISTRY", address(dappRegistry), address(newRegistry));
        dappRegistry = newRegistry;
    }

    // ── IPermissionOracle ─────────────────────────────────────────────────────

    /// @inheritdoc IPermissionOracle
    function orgOf(address wallet) public view override returns (address) {
        return orgRegistry.orgOf(wallet);
    }

    /// @inheritdoc IPermissionOracle
    function hasPermission(address wallet, bytes32 permission) public view override returns (bool) {
        address org = orgRegistry.orgOf(wallet);
        if (org == address(0) || !orgRegistry.isOrgActive(org)) {
            return false;
        }
        if (!permissionRegistry.orgGranted(org, permission)) {
            return false;
        }
        if (!permissionRegistry.isRoleRestricted(org, permission)) {
            return true;
        }
        bytes32[] memory roles = orgRegistry.memberRoles(wallet);
        for (uint256 i = 0; i < roles.length; i++) {
            if (permissionRegistry.roleGranted(org, roles[i], permission)) {
                return true;
            }
        }
        return false;
    }

    /// @inheritdoc IPermissionOracle
    function isActiveMember(address wallet) public view override returns (bool) {
        return orgRegistry.isActiveMember(wallet);
    }

    /// @inheritdoc IPermissionOracle
    function hasRole(address wallet, bytes32 role)
        public
        view
        override(IPermissionOracle)
        returns (bool)
    {
        return orgRegistry.isActiveMember(wallet) && orgRegistry.memberHasRole(wallet, role);
    }

    /// @inheritdoc IPermissionOracle
    function hasClaimTopic(address wallet, uint256 topic) public view override returns (bool) {
        address org = orgRegistry.orgOf(wallet);
        if (org == address(0) || !orgRegistry.isOrgActive(org) || org.code.length == 0) {
            return false;
        }

        try IIdentity(org).getClaimIdsByTopic(topic) returns (bytes32[] memory claimIds) {
            for (uint256 i = 0; i < claimIds.length; i++) {
                if (_isClaimValid(IIdentity(org), topic, claimIds[i])) {
                    return true;
                }
            }
            return false;
        } catch {
            return false;
        }
    }

    /// @inheritdoc IPermissionOracle
    function check(address wallet, bytes32 permission, uint256[] calldata topics)
        external
        view
        override
        returns (bool)
    {
        if (!hasPermission(wallet, permission)) {
            return false;
        }
        for (uint256 i = 0; i < topics.length; i++) {
            if (!hasClaimTopic(wallet, topics[i])) {
                return false;
            }
        }
        return true;
    }

    /// @inheritdoc IPermissionOracle
    function isApprovedInstance(address dappInstance) external view override returns (bool) {
        if (address(dappRegistry) == address(0)) {
            return false;
        }
        try dappRegistry.isApprovedInstance(dappInstance) returns (bool approved) {
            return approved;
        } catch {
            return false;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /// @dev A claim counts when its issuer is trusted for the topic in the ecosystem TIR
    ///      and the issuer itself still considers the claim valid (signature + revocation).
    function _isClaimValid(IIdentity identity, uint256 topic, bytes32 claimId) private view returns (bool) {
        try identity.getClaim(claimId) returns (
            uint256 claimTopic,
            uint256,
            address issuer,
            bytes memory signature,
            bytes memory data,
            string memory
        ) {
            if (claimTopic != topic || issuer == address(0)) {
                return false;
            }
            if (!trustedIssuersRegistry.hasClaimTopic(issuer, topic)) {
                return false;
            }
            try IClaimIssuer(issuer).isClaimValid(identity, topic, signature, data) returns (bool valid) {
                return valid;
            } catch {
                return false;
            }
        } catch {
            return false;
        }
    }
}
