// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/compliance/WhitelistRegistry.sol";

/// @notice Deploy the core eWpG Registry infrastructure contracts.
///         Reads REGISTRY_WALLET_PRIVATE_KEY from the environment.
///         Usage:
///           forge script script/Deploy.s.sol --rpc-url <rpc> --broadcast
contract Deploy is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);

        WhitelistRegistry whitelistRegistry = new WhitelistRegistry();
        AssetTokenFactory factory = new AssetTokenFactory(deployer);

        vm.stopBroadcast();

        console.log("Deployer / registry wallet :", deployer);
        console.log("WhitelistRegistry          :", address(whitelistRegistry));
        console.log("AssetTokenFactory          :", address(factory));
    }
}
