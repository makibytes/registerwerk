// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";

/// @notice Deploys the per-chain ONCHAINID ClaimIssuer the backend issues KYC/AML claims through
///         (T2-21). Set the logged address as `CLAIM_ISSUER_<CHAIN>`
///         (`registerwerk.contracts.claim-issuer.<chain>`), then register it as trusted issuer on
///         existing T-REX suites (`POST /api/v1/assets/{assetId}/erc3643/{deploymentId}/trusted-issuers`, topics 1,2) and,
///         for PermissionOracle, in the EcosystemTrustedIssuersRegistry. New suites trust it
///         automatically.
///
///         The MANAGEMENT key must be the backend's registry signer for that chain: it signs every
///         claim (`isClaimValid` requires a CLAIM/MANAGEMENT key) and calls `revokeClaimBySignature`.
///         Env: `CLAIM_ISSUER_MANAGEMENT_KEY` (address, defaults to the broadcasting deployer).
contract DeployClaimIssuer is Script {
    function run() external returns (ClaimIssuer issuer) {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address managementKey = vm.envOr("CLAIM_ISSUER_MANAGEMENT_KEY", vm.addr(deployerKey));

        vm.startBroadcast(deployerKey);
        issuer = new ClaimIssuer(managementKey);
        vm.stopBroadcast();

        console.log("ClaimIssuer                :", address(issuer));
        console.log("Management key (signer)    :", managementKey);
    }
}
