// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/AccessControl.sol";
import "./interfaces/IClaimIssuer.sol";

/// @title EcosystemTrustedIssuersRegistry
/// @notice Ecosystem-wide registry of claim issuers trusted for ONCHAINID claim topics
///         (1 = KYC, 2 = AML, 3 = Accreditation, 4 = Nominee — held by a licensed
///         custodian/CASP permitted to hold pooled positions on behalf of investors it KYCs
///         and tracks itself, see EwpgComplianceModule.setNomineePool). View-compatible with
///         the T-REX ITrustedIssuersRegistry so per-token registries could be swapped in later.
contract EcosystemTrustedIssuersRegistry is AccessControl {
    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    address[] private _issuers;
    mapping(address => uint256[]) private _issuerTopics;
    mapping(uint256 => address[]) private _issuersByTopic;

    event TrustedIssuerAdded(address indexed issuer, uint256[] claimTopics);
    event TrustedIssuerRemoved(address indexed issuer);
    event ClaimTopicsUpdated(address indexed issuer, uint256[] claimTopics);

    constructor(address admin) {
        require(admin != address(0), "EcosystemTIR: zero admin address");
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
    }

    // ── Management (operator) ─────────────────────────────────────────────────

    function addTrustedIssuer(address issuer, uint256[] calldata claimTopics)
        external
        onlyRole(OPERATOR_ROLE)
    {
        require(issuer != address(0), "EcosystemTIR: zero issuer address");
        require(claimTopics.length > 0, "EcosystemTIR: no claim topics");
        require(_issuerTopics[issuer].length == 0, "EcosystemTIR: issuer already trusted");

        _issuers.push(issuer);
        _issuerTopics[issuer] = claimTopics;
        for (uint256 i = 0; i < claimTopics.length; i++) {
            _issuersByTopic[claimTopics[i]].push(issuer);
        }
        emit TrustedIssuerAdded(issuer, claimTopics);
    }

    function removeTrustedIssuer(address issuer) external onlyRole(OPERATOR_ROLE) {
        require(_issuerTopics[issuer].length > 0, "EcosystemTIR: issuer not trusted");

        uint256[] memory topics = _issuerTopics[issuer];
        for (uint256 i = 0; i < topics.length; i++) {
            _removeFromList(_issuersByTopic[topics[i]], issuer);
        }
        _removeFromList(_issuers, issuer);
        delete _issuerTopics[issuer];
        emit TrustedIssuerRemoved(issuer);
    }

    function updateIssuerClaimTopics(address issuer, uint256[] calldata claimTopics)
        external
        onlyRole(OPERATOR_ROLE)
    {
        require(_issuerTopics[issuer].length > 0, "EcosystemTIR: issuer not trusted");
        require(claimTopics.length > 0, "EcosystemTIR: no claim topics");

        uint256[] memory oldTopics = _issuerTopics[issuer];
        for (uint256 i = 0; i < oldTopics.length; i++) {
            _removeFromList(_issuersByTopic[oldTopics[i]], issuer);
        }
        _issuerTopics[issuer] = claimTopics;
        for (uint256 i = 0; i < claimTopics.length; i++) {
            _issuersByTopic[claimTopics[i]].push(issuer);
        }
        emit ClaimTopicsUpdated(issuer, claimTopics);
    }

    // ── Views (T-REX ITrustedIssuersRegistry-compatible) ──────────────────────

    function getTrustedIssuers() external view returns (address[] memory) {
        return _issuers;
    }

    function getTrustedIssuersForClaimTopic(uint256 claimTopic) external view returns (address[] memory) {
        return _issuersByTopic[claimTopic];
    }

    function isTrustedIssuer(address issuer) public view returns (bool) {
        return _issuerTopics[issuer].length > 0;
    }

    function getTrustedIssuerClaimTopics(address issuer) external view returns (uint256[] memory) {
        return _issuerTopics[issuer];
    }

    function hasClaimTopic(address issuer, uint256 claimTopic) public view returns (bool) {
        uint256[] storage topics = _issuerTopics[issuer];
        for (uint256 i = 0; i < topics.length; i++) {
            if (topics[i] == claimTopic) {
                return true;
            }
        }
        return false;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    function _removeFromList(address[] storage list, address value) private {
        for (uint256 i = 0; i < list.length; i++) {
            if (list[i] == value) {
                list[i] = list[list.length - 1];
                list.pop();
                return;
            }
        }
    }
}
