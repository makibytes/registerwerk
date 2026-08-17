// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "../src/compliance/WhitelistRegistry.sol";

/// @notice Deploy a standalone WhitelistRegistry and optionally transfer
///         ownership to a new registry wallet.
///
///       Required env vars:
///         REGISTRY_WALLET_PRIVATE_KEY  — deployer / current owner private key
///         NEW_REGISTRY_WALLET          — (optional) new owner address; if omitted
///                                        ownership stays with the deployer
///
///       Usage:
///         forge script script/UpgradeCompliance.s.sol --rpc-url <rpc> --broadcast
contract UpgradeCompliance is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        // Optional: pass a new owner via env var; fall back to deployer.
        address newOwner = vm.envOr("NEW_REGISTRY_WALLET", deployer);

        vm.startBroadcast(deployerKey);

        // Deploy a fresh WhitelistRegistry.
        WhitelistRegistry newRegistry = new WhitelistRegistry();

        // Transfer ownership if a distinct new owner was supplied.
        if (newOwner != deployer) {
            newRegistry.transferOwnership(newOwner);
            console.log("Ownership transferred to:", newOwner);
        }

        vm.stopBroadcast();

        console.log(unicode"=== eWpG Registry — Compliance Upgrade ===");
        console.log("New WhitelistRegistry :", address(newRegistry));
        console.log("Owner                 :", newOwner);
    }
}
