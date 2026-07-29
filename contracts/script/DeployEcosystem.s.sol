// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "../src/ecosystem/DappRegistry.sol";
import "../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../src/ecosystem/OrgRegistry.sol";
import "../src/ecosystem/PermissionOracle.sol";
import "../src/ecosystem/PermissionRegistry.sol";

/// @notice Deploy the Registerwerk ecosystem suite (org identity + permission framework).
///         Reads REGISTRY_WALLET_PRIVATE_KEY from the environment.
///         Usage:
///           forge script script/DeployEcosystem.s.sol --rpc-url <rpc> --broadcast
///
///         The printed addresses map to backend config keys:
///           registerwerk.contracts.org-registry.<chain>-<network>
///           registerwerk.contracts.permission-registry.<chain>-<network>
///           registerwerk.contracts.ecosystem-tir.<chain>-<network>
///           registerwerk.contracts.permission-oracle.<chain>-<network>
///           registerwerk.contracts.dapp-registry.<chain>-<network>
contract DeployEcosystem is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);

        OrgRegistry orgRegistry = new OrgRegistry(deployer);
        PermissionRegistry permissionRegistry = new PermissionRegistry(deployer, orgRegistry);
        EcosystemTrustedIssuersRegistry tir = new EcosystemTrustedIssuersRegistry(deployer);
        PermissionOracle oracle = new PermissionOracle(deployer, orgRegistry, permissionRegistry, tir);
        DappRegistry dappRegistry = new DappRegistry(deployer, orgRegistry);
        oracle.setDappRegistry(IDappRegistryView(address(dappRegistry)));

        vm.stopBroadcast();

        console.log("Deployer / registry wallet     :", deployer);
        console.log("OrgRegistry                    :", address(orgRegistry));
        console.log("PermissionRegistry             :", address(permissionRegistry));
        console.log("EcosystemTrustedIssuersRegistry:", address(tir));
        console.log("PermissionOracle               :", address(oracle));
        console.log("DappRegistry                   :", address(dappRegistry));
    }
}
