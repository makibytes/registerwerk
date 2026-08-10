// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../src/ecosystem/OrgRegistry.sol";
import "../src/ecosystem/PermissionOracle.sol";
import "../src/ecosystem/PermissionRegistry.sol";
import "../src/examples/MockStablecoin.sol";
import "../src/lending/EwpgRepoMarket.sol";
import "../src/lending/EwpgRepoMarketFactory.sol";
import "../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../test/ecosystem/mocks/MockClaimIssuer.sol";
import "../test/ecosystem/mocks/MockOnchainId.sol";

/// @notice Self-contained Anvil fixture used by docker-compose. It creates two isolated
/// securities-backed lending markets and gives the five demo trading companies importable
/// Anvil wallets, real collateral balances, KYC claims, borrow permission, and repayment cash.
/// It must never be used outside a disposable local/demo chain.
contract DeployLocalLendingDemo is Script {
    uint256 private constant KYC_TOPIC = 1;
    address private constant NORDBANK = 0x70997970C51812dc3A010C7d01b50e0d17dc79C8;
    address private constant RHEINISCHE = 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC;
    address private constant AURORA = 0x90F79bf6EB2c4f870365E785982E1f101E93b906;
    address private constant FRANKFURT = 0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65;
    address private constant WUERTTEMBERG = 0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc;

    OrgRegistry private orgRegistry;
    PermissionRegistry private permissions;
    EcosystemTrustedIssuersRegistry private trustedIssuers;
    MockClaimIssuer private claimIssuer;
    MockStablecoin private loanToken;
    MockStablecoin private greenBond;
    MockStablecoin private infraNote;
    RegisterwerkNavOracle private navOracle;
    EwpgRepoMarketFactory private factory;

    function run() external {
        uint256 deployerKey = vm.envOr(
            "LOCAL_DEMO_DEPLOYER_KEY",
            uint256(0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80)
        );
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);
        orgRegistry = new OrgRegistry(deployer);
        permissions = new PermissionRegistry(deployer, orgRegistry);
        trustedIssuers = new EcosystemTrustedIssuersRegistry(deployer);
        PermissionOracle permissionOracle =
            new PermissionOracle(deployer, orgRegistry, permissions, trustedIssuers);
        claimIssuer = new MockClaimIssuer();
        uint256[] memory topics = new uint256[](1);
        topics[0] = KYC_TOPIC;
        trustedIssuers.addTrustedIssuer(address(claimIssuer), topics);

        loanToken = new MockStablecoin("Demo Euro", "DEMOEUR", 6);
        greenBond = new MockStablecoin("Meridian Green Bond", "MGB24", 0);
        infraNote = new MockStablecoin("Aurora Infrastructure Note", "AIN25", 0);
        navOracle = new RegisterwerkNavOracle(permissionOracle);
        factory = new EwpgRepoMarketFactory(permissionOracle);

        address operatorOrg = _registerMember(deployer);
        _defineAndGrant(operatorOrg, "repo-markets.create-market");
        _defineAndGrant(operatorOrg, "repo-markets.push-price");
        _defineAndGrant(operatorOrg, "repo-markets.override-price");
        _defineAndGrant(operatorOrg, "repo-facility.borrow");

        address[5] memory traders = [NORDBANK, RHEINISCHE, AURORA, FRANKFURT, WUERTTEMBERG];
        for (uint256 i = 0; i < traders.length; i++) {
            address org = _registerMember(traders[i]);
            permissions.grantToOrg(org, keccak256("repo-facility.borrow"));
            loanToken.mint(traders[i], 250_000e6);
        }

        greenBond.mint(NORDBANK, 5_000);
        greenBond.mint(RHEINISCHE, 3_000);
        greenBond.mint(AURORA, 2_000);
        infraNote.mint(RHEINISCHE, 20_000);
        infraNote.mint(WUERTTEMBERG, 15_000);
        infraNote.mint(FRANKFURT, 10_000);

        navOracle.pushPrice(address(greenBond), 1_050e6);
        navOracle.pushPrice(address(infraNote), 502_500_000);
        address greenMarket = factory.createMarket(
            loanToken, greenBond, navOracle, 7000, 8000, 500, 0.02e18, 0.18e18, 365 days, 730 days
        );
        address infraMarket = factory.createMarket(
            loanToken, infraNote, navOracle, 6500, 7800, 500, 0.025e18, 0.20e18, 365 days, 730 days
        );

        loanToken.mint(deployer, 10_000_000e6);
        loanToken.approve(greenMarket, type(uint256).max);
        loanToken.approve(infraMarket, type(uint256).max);
        EwpgRepoMarket(greenMarket).supply(5_000_000e6);
        EwpgRepoMarket(infraMarket).supply(5_000_000e6);
        vm.stopBroadcast();

        string memory output = string.concat(
            "LOAN_TOKEN=", vm.toString(address(loanToken)), "\n",
            "NAV_ORACLE=", vm.toString(address(navOracle)), "\n",
            "GREEN_BOND_TOKEN=", vm.toString(address(greenBond)), "\n",
            "GREEN_BOND_MARKET=", vm.toString(greenMarket), "\n",
            "INFRA_NOTE_TOKEN=", vm.toString(address(infraNote)), "\n",
            "INFRA_NOTE_MARKET=", vm.toString(infraMarket), "\n"
        );
        vm.writeFile(vm.envOr("LOCAL_DEMO_OUTPUT", string("/output/demo.env")), output);
    }

    function _registerMember(address wallet) private returns (address org) {
        MockOnchainId identity = new MockOnchainId();
        identity.addClaim(KYC_TOPIC, address(claimIssuer), hex"01", hex"01");
        org = address(identity);
        orgRegistry.registerOrg(org, 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(org, wallet, roles, "");
    }

    function _defineAndGrant(address org, string memory code) private {
        bytes32 id = keccak256(bytes(code));
        permissions.definePermission(id, code);
        permissions.grantToOrg(org, id);
    }
}
