// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import "../src/compliance/EwpgModularCompliance.sol";
import "../test/helpers/TrexSuiteDeployer.sol";

/// @notice ERC-3643 is pinned by upstream to Solidity 0.8.30, so its local demo deployment is
/// intentionally compiled separately from the 0.8.36 token suite and merged into demo.env.
contract DeployLocalTrexDemo is Script {
    function run() external {
        uint256 deployerKey = vm.envOr(
            "LOCAL_DEMO_DEPLOYER_KEY", uint256(0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80)
        );
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);
        TrexDeployment memory trex = TrexSuiteDeployer.deploy(deployer);
        ClaimIssuer issuer = new ClaimIssuer(deployer);
        EwpgComplianceModule module = new EwpgComplianceModule();
        address token = _deploySuite(trex, issuer, module, deployer);
        vm.stopBroadcast();

        string memory output = string.concat(
            "TREX_FACTORY=",
            vm.toString(address(trex.factory)),
            "\n",
            "ID_FACTORY=",
            vm.toString(address(trex.idFactory)),
            "\n",
            "TREX_IMPLEMENTATION_AUTHORITY=",
            vm.toString(address(trex.trexAuthority)),
            "\n",
            "ONCHAIN_ID_IMPLEMENTATION_AUTHORITY=",
            vm.toString(address(trex.onchainIdAuthority)),
            "\n",
            "DEMO_ERC3643_TOKEN=",
            vm.toString(token),
            "\n"
        );
        vm.writeFile(vm.envOr("LOCAL_TREX_OUTPUT", string("/output/trex.env")), output);
    }

    function _deploySuite(TrexDeployment memory trex, ClaimIssuer issuer, EwpgComplianceModule module, address deployer)
        private
        returns (address)
    {
        address[] memory irAgents = new address[](1);
        irAgents[0] = deployer;
        address[] memory modules = new address[](1);
        modules[0] = address(module);
        ITREXFactory.TokenDetails memory details = ITREXFactory.TokenDetails({
            owner: deployer,
            name: "Registerwerk Compliant Equity",
            symbol: "RWCE",
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: irAgents,
            tokenAgents: new address[](0),
            complianceModules: modules,
            complianceSettings: new bytes[](0)
        });
        uint256[] memory topics = new uint256[](1);
        topics[0] = 1;
        address[] memory issuers = new address[](1);
        issuers[0] = address(issuer);
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = topics;
        ITREXFactory.ClaimDetails memory claims =
            ITREXFactory.ClaimDetails({claimTopics: topics, issuers: issuers, issuerClaims: issuerClaims});
        return trex.factory.deployEwpgSuite(keccak256("demo-erc3643"), "registerwerk-demo-erc3643", details, claims);
    }
}
