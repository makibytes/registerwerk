// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";

import "../src/compliance/EwpgModularCompliance.sol";
import "../test/helpers/TrexSuiteDeployer.sol";

/// @notice Deploy a full, real T-REX (ERC-3643) suite for an eWpG bond: the T-REX
///         implementation authority, ONCHAINID factory, a self-contained demo claim
///         issuer, the {EwpgComplianceModule}, and the bond token itself via
///         {EwpgTREXFactory.deployEwpgSuite}.
///
///         This is the deployment recipe demonstrated end to end by
///         `test/examples/EwpgBondDesk.t.sol` — run that test first if you want to see
///         the full flow (including investor onboarding) before deploying for real.
///
///         Reads REGISTRY_WALLET_PRIVATE_KEY from the environment; the same key both
///         deploys the suite and becomes the bond's owner / identity-registry agent /
///         demo claim issuer management key. Optional overrides:
///           EWPG_BOND_ASSET_ID  (bytes32, default keccak256("demo-ewpg-bond"))
///           EWPG_BOND_SALT      (string,  default "demo-ewpg-bond-2029")
///           EWPG_BOND_NAME      (string,  default "Registerwerk Demo Infrastructure Bond 2029")
///           EWPG_BOND_SYMBOL    (string,  default "RWDB29")
///
///         Usage:
///           forge script script/DeployEwpgTrexBond.s.sol --rpc-url <rpc> --broadcast
///
///         Deploy the {EwpgBondDesk} dApp against the resulting token address with
///         `script/DeployExampleDapps.s.sol`.
///
/// @dev Struct-building is split into small helper functions rather than inlined in
///      `run()` — with everything in one function, the legacy Solidity codegen hits
///      "stack too deep" once enough locals (the T-REX suite handles, the token/claim
///      detail structs and their array fields, etc.) are simultaneously live.
contract DeployEwpgTrexBond is Script {
    uint256 internal constant TOPIC_KYC = 1;
    uint256 internal constant TOPIC_AML = 2;

    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);
        (
            TrexDeployment memory trex,
            EwpgComplianceModule complianceModule,
            address trustedIssuer,
            bytes32 assetId,
            string memory salt,
            address tokenAddress
        ) = _deploySuite(deployer);
        vm.stopBroadcast();

        _logResult(deployer, trex, complianceModule, trustedIssuer, assetId, salt, tokenAddress);
    }

    function _deploySuite(address deployer)
        internal
        returns (
            TrexDeployment memory trex,
            EwpgComplianceModule complianceModule,
            address trustedIssuer,
            bytes32 assetId,
            string memory salt,
            address tokenAddress
        )
    {
        trex = TrexSuiteDeployer.deploy(deployer);
        complianceModule = new EwpgComplianceModule();
        trustedIssuer = _resolveTrustedIssuer(deployer);

        assetId = vm.envOr("EWPG_BOND_ASSET_ID", keccak256("demo-ewpg-bond"));
        salt = vm.envOr("EWPG_BOND_SALT", string("demo-ewpg-bond-2029"));

        tokenAddress = trex.factory.deployEwpgSuite(
            assetId, salt, _tokenDetails(deployer, complianceModule), _claimDetails(trustedIssuer)
        );
    }

    /// @dev Self-contained demo claim issuer: the deployer's own key doubles as the
    ///      issuer's management key, so it can both mint identities and sign claims.
    ///      Point EWPG_BOND_TRUSTED_ISSUER at a real institutional issuer for production.
    function _resolveTrustedIssuer(address deployer) internal returns (address trustedIssuer) {
        trustedIssuer = vm.envOr("EWPG_BOND_TRUSTED_ISSUER", address(0));
        if (trustedIssuer == address(0)) {
            trustedIssuer = address(new ClaimIssuer(deployer));
        }
    }

    function _tokenDetails(address deployer, EwpgComplianceModule complianceModule)
        internal
        returns (ITREXFactory.TokenDetails memory details)
    {
        address[] memory irAgents = new address[](1);
        irAgents[0] = deployer;
        address[] memory complianceModules = new address[](1);
        complianceModules[0] = address(complianceModule);

        details = ITREXFactory.TokenDetails({
            owner: deployer,
            name: vm.envOr("EWPG_BOND_NAME", string("Registerwerk Demo Infrastructure Bond 2029")),
            symbol: vm.envOr("EWPG_BOND_SYMBOL", string("RWDB29")),
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: irAgents,
            tokenAgents: new address[](0),
            complianceModules: complianceModules,
            complianceSettings: new bytes[](0)
        });
    }

    function _claimDetails(address trustedIssuer) internal pure returns (ITREXFactory.ClaimDetails memory details) {
        uint256[] memory claimTopics = new uint256[](2);
        claimTopics[0] = TOPIC_KYC;
        claimTopics[1] = TOPIC_AML;
        address[] memory issuers = new address[](1);
        issuers[0] = trustedIssuer;
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = claimTopics;

        details = ITREXFactory.ClaimDetails({claimTopics: claimTopics, issuers: issuers, issuerClaims: issuerClaims});
    }

    function _logResult(
        address deployer,
        TrexDeployment memory trex,
        EwpgComplianceModule complianceModule,
        address trustedIssuer,
        bytes32 assetId,
        string memory salt,
        address tokenAddress
    ) internal view {
        IERC3643 bond = IERC3643(tokenAddress);
        console.log("Deployer / registry wallet     :", deployer);
        console.log("EwpgTREXFactory                :", address(trex.factory));
        console.log("IdFactory (ONCHAINID)          :", address(trex.idFactory));
        console.log("TREXImplementationAuthority    :", address(trex.trexAuthority));
        console.log("EwpgComplianceModule           :", address(complianceModule));
        console.log("Trusted claim issuer           :", trustedIssuer);
        console.log("Bond token (EwpgERC3643)       :", tokenAddress);
        console.log("Identity Registry              :", address(bond.identityRegistry()));
        console.log("Modular Compliance             :", address(bond.compliance()));
        console.log("Bond assetId                   :", vm.toString(assetId));
        console.log("Bond salt                      :", salt);
    }
}
