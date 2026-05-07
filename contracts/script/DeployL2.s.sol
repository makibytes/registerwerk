// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/compliance/WhitelistRegistry.sol";

/// @notice Deploy the core eWpG Registry infrastructure contracts to an EVM L2.
///         Works on Arbitrum, Avalanche C-Chain, and Optimism (and their testnets).
///         Reads REGISTRY_WALLET_PRIVATE_KEY from the environment.
///
///         Usage examples:
///
///           # Arbitrum Sepolia
///           forge script script/DeployL2.s.sol \
///             --rpc-url $ARBITRUM_SEPOLIA_RPC \
///             --broadcast --verify \
///             --etherscan-api-key $ARBISCAN_API_KEY
///
///           # Avalanche Fuji
///           forge script script/DeployL2.s.sol \
///             --rpc-url $AVALANCHE_FUJI_RPC \
///             --broadcast --verify \
///             --etherscan-api-key $SNOWTRACE_API_KEY
///
///           # Optimism Sepolia
///           forge script script/DeployL2.s.sol \
///             --rpc-url $OPTIMISM_SEPOLIA_RPC \
///             --broadcast --verify \
///             --etherscan-api-key $OPTIMISM_ETHERSCAN_API_KEY
///
///         After a successful broadcast, copy the printed addresses into
///         application.yml under registerwerk.contracts.*.
contract DeployL2 is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);

        WhitelistRegistry whitelistRegistry = new WhitelistRegistry();
        AssetTokenFactory factory = new AssetTokenFactory(deployer);

        vm.stopBroadcast();

        console.log("=== eWpG Registry — L2 Deployment ===");
        console.log("Chain ID                   :", block.chainid);
        console.log("Deployer / registry wallet :", deployer);
        console.log("WhitelistRegistry          :", address(whitelistRegistry));
        console.log("AssetTokenFactory          :", address(factory));
        console.log("");
        console.log("Paste into application.yml:");
        console.log("  registerwerk.contracts.asset-token-factory.<chain>-<network>:", address(factory));
        console.log("  registerwerk.contracts.id-factory.<chain>-<network>: <deploy separately>");
    }
}
