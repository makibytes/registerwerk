// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/compliance/WhitelistRegistry.sol";

/// @notice Deploy the core eWpG Registry infrastructure contracts to a testnet.
///         Reads REGISTRY_WALLET_PRIVATE_KEY from the environment.
///         Usage (Sepolia example):
///           forge script script/DeployTestnet.s.sol \
///             --rpc-url $ETH_SEPOLIA_RPC \
///             --broadcast \
///             --verify \
///             --etherscan-api-key $ETHERSCAN_API_KEY
///
///         Supported testnet RPC env vars (see foundry.toml):
///           ETH_SEPOLIA_RPC, POLYGON_AMOY_RPC, BASE_SEPOLIA_RPC
contract DeployTestnet is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);

        WhitelistRegistry whitelistRegistry = new WhitelistRegistry();
        AssetTokenFactory factory = new AssetTokenFactory(deployer);

        vm.stopBroadcast();

        console.log(unicode"=== eWpG Registry — Testnet Deployment ===");
        console.log("Deployer / registry wallet :", deployer);
        console.log("WhitelistRegistry          :", address(whitelistRegistry));
        console.log("AssetTokenFactory          :", address(factory));
        console.log("Chain ID                   :", block.chainid);
    }
}
