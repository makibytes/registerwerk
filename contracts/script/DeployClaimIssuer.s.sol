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
///         The MANAGEMENT key is, by default, the backend's registry signer for that chain (the
///         broadcasting deployer, `REGISTRY_WALLET_PRIVATE_KEY`): it signs every claim
///         (`isClaimValid` requires a CLAIM_SIGNER or MANAGEMENT key) and the backend calls
///         `revokeClaimBySignature` with it, which ONCHAINID restricts to MANAGEMENT keys. Env:
///         `CLAIM_ISSUER_MANAGEMENT_KEY` (address, OPTIONAL — defaults to the broadcasting deployer).
///
///         Trade-off: with the default, the hot signing key also controls the issuer's key set
///         (`addKey`/`removeKey`) and can upgrade it. Overriding it with a separate (cold /
///         multisig) key is possible but then that key must authorise the registry signer
///         afterwards — `addKey(keccak256(abi.encode(registrySigner)), 3 /* CLAIM_SIGNER */, 1 /* ECDSA */)`
///         to sign claims, and a MANAGEMENT (1) key for it as well for as long as the backend
///         itself revokes claims, otherwise `revokeClaimBySignature` reverts.
contract DeployClaimIssuer is Script {
    function run() external returns (ClaimIssuer issuer) {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address managementKey = vm.envOr("CLAIM_ISSUER_MANAGEMENT_KEY", vm.addr(deployerKey));

        vm.startBroadcast(deployerKey);
        issuer = new ClaimIssuer(managementKey);
        vm.stopBroadcast();

        console.log("ClaimIssuer                :", address(issuer));
        console.log("Management key (signer)    :", managementKey);
        if (managementKey != vm.addr(deployerKey)) {
            console.log("Management key differs from the registry signer: from it, addKey(keccak256(abi.encode(signer)), 3, 1)");
            console.log("(and a MANAGEMENT key for the signer if the backend revokes claims) before the first claim.");
        }
    }
}
