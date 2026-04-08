// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

struct TokenDetails {
    address owner;
    string name;
    string symbol;
    uint8 decimals;
    address irs;          // Identity Registry Storage (address(0) = deploy new)
    address ONCHAINID;    // Token's ONCHAINID (address(0) = deploy new)
    address[] irAgents;
    address[] tokenAgents;
    address[] complianceModules;
    bytes[] complianceSettings;
}

struct ClaimDetails {
    uint256[] claimTopics;
    address[] issuers;
    uint256[][] issuerClaims;
}

interface IERC3643Suite {
    function tokenAddress() external view returns (address);
    function identityRegistry() external view returns (address);
    function identityRegistryStorage() external view returns (address);
    function compliance() external view returns (address);
    function claimTopicsRegistry() external view returns (address);
    function trustedIssuersRegistry() external view returns (address);
}
