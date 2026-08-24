// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../src/ecosystem/OrgRegistry.sol";
import "../src/ecosystem/PermissionOracle.sol";
import "../src/ecosystem/PermissionRegistry.sol";
import "../src/ecosystem/DappRegistry.sol";
import "../src/ecosystem/RegisterwerkDeploymentRegistry.sol";
import "@openzeppelin/contracts/proxy/ERC1967/ERC1967Proxy.sol";
import "../src/examples/MockStablecoin.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/factory/AssetTokenFactoryBootstrap.sol";
import "../src/tokens/EwpgERC20.sol";
import "../src/tokens/EwpgERC721.sol";
import "../src/tokens/EwpgERC1155.sol";
import "../src/tokens/EwpgERC3525.sol";
import "../src/tokens/EwpgERC4626.sol";
import "../src/tokens/EwpgERC7540.sol";
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
    PermissionOracle private permissionOracle;
    DappRegistry private dappRegistry;
    AssetTokenFactory private assetFactory;
    RegisterwerkDeploymentRegistry private deploymentRegistry;
    address private erc20Token;
    address private erc721Token;
    address private erc1155Token;
    address private erc3525Token;
    address private erc4626Vault;
    address private erc7540Vault;

    function run() external {
        uint256 deployerKey = vm.envOr(
            "LOCAL_DEMO_DEPLOYER_KEY", uint256(0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80)
        );
        address deployer = vm.addr(deployerKey);

        vm.startBroadcast(deployerKey);
        orgRegistry = new OrgRegistry(deployer);
        permissions = new PermissionRegistry(deployer, orgRegistry);
        trustedIssuers = new EcosystemTrustedIssuersRegistry(deployer);
        permissionOracle = new PermissionOracle(deployer, orgRegistry, permissions, trustedIssuers);
        dappRegistry = new DappRegistry(deployer, orgRegistry);
        permissionOracle.setDappRegistry(IDappRegistryView(address(dappRegistry)));
        claimIssuer = new MockClaimIssuer();
        uint256[] memory topics = new uint256[](1);
        topics[0] = KYC_TOPIC;
        trustedIssuers.addTrustedIssuer(address(claimIssuer), topics);

        loanToken = new MockStablecoin("Demo Euro", "DEMOEUR", 6);
        greenBond = new MockStablecoin("Meridian Green Bond", "MGB24", 0);
        infraNote = new MockStablecoin("Aurora Infrastructure Note", "AIN25", 0);
        assetFactory = new AssetTokenFactory(deployer);
        AssetTokenFactoryBootstrap.configure(assetFactory, deployer);
        _deployStandardProducts(deployer);
        RegisterwerkDeploymentRegistry registryImplementation = new RegisterwerkDeploymentRegistry();
        deploymentRegistry = RegisterwerkDeploymentRegistry(
            address(
                new ERC1967Proxy(
                    address(registryImplementation),
                    abi.encodeCall(RegisterwerkDeploymentRegistry.initialize, (deployer))
                )
            )
        );
        _registerStandardProducts();
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
            (bool funded,) = payable(traders[i]).call{value: 100 ether}("");
            require(funded, "native gas funding failed");
        }

        _seedStandardProducts(deployer, traders);

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
            loanToken, infraNote, navOracle, 6500, 7800, 500, 0.025e18, 0.2e18, 365 days, 730 days
        );

        loanToken.mint(deployer, 10_000_000e6);
        loanToken.approve(greenMarket, type(uint256).max);
        loanToken.approve(infraMarket, type(uint256).max);
        EwpgRepoMarket(greenMarket).supply(5_000_000e6);
        EwpgRepoMarket(infraMarket).supply(5_000_000e6);
        vm.stopBroadcast();

        _writeManifest(deployer, greenMarket, infraMarket);
    }

    function _deployStandardProducts(address deployer) private {
        erc20Token = assetFactory.deployToken(0, "Registerwerk Demo Euro Bond", "RWDEB", keccak256("demo-erc20"));
        erc721Token = assetFactory.deployToken(1, "Registerwerk Unique Asset Notes", "RWUAN", keccak256("demo-erc721"));
        erc1155Token = assetFactory.deployToken(2, "", "RWMCT", keccak256("demo-erc1155"));
        erc3525Token = assetFactory.deployToken(3, "Registerwerk Maturity Notes", "RWMN", keccak256("demo-erc3525"));
        erc4626Vault = assetFactory.deployVault(
            4, "Registerwerk Liquidity Fund", "RWLF", keccak256("demo-erc4626"), address(loanToken)
        );
        erc7540Vault = assetFactory.deployVault(
            5, "Registerwerk Private Credit Fund", "RWPC", keccak256("demo-erc7540"), address(loanToken)
        );

        EwpgERC4626(erc4626Vault).setNavPerShare(1e18, block.timestamp, keccak256("demo-nav-4626"));
        EwpgERC7540(erc7540Vault).setNavPerShare(1.025e18, block.timestamp, keccak256("demo-nav-7540"));
        EwpgERC7540(erc7540Vault).setMinSettlementDelay(0);
        _whitelistAll(deployer);
    }

    function _whitelistAll(address deployer) private {
        address[6] memory accounts = [deployer, NORDBANK, RHEINISCHE, AURORA, FRANKFURT, WUERTTEMBERG];
        for (uint256 i = 0; i < accounts.length; i++) {
            EwpgERC20(erc20Token).whitelist(accounts[i]);
            EwpgERC721(erc721Token).whitelist(accounts[i]);
            EwpgERC1155(erc1155Token).whitelist(accounts[i]);
            EwpgERC3525(erc3525Token).whitelist(accounts[i]);
            EwpgERC4626(erc4626Vault).whitelist(accounts[i]);
            EwpgERC7540(erc7540Vault).whitelist(accounts[i]);
        }
    }

    function _seedStandardProducts(address deployer, address[5] memory traders) private {
        for (uint256 i = 0; i < traders.length; i++) {
            EwpgERC20(erc20Token).mint(traders[i], (i + 1) * 10_000 ether);
            EwpgERC721(erc721Token).mint(traders[i], 1001 + i);
            EwpgERC1155(erc1155Token).mint(traders[i], (i % 2) + 1, (i + 1) * 100, "");
            EwpgERC3525(erc3525Token).mint(traders[i], 2030 + (i % 2), (i + 1) * 50_000);
        }

        loanToken.mint(deployer, 2_000_000e6);
        loanToken.approve(erc4626Vault, 1_000_000e6);
        EwpgERC4626(erc4626Vault).deposit(1_000_000e6, deployer);
        loanToken.approve(erc7540Vault, 500_000e6);
        uint256 requestId = EwpgERC7540(erc7540Vault).requestDeposit(500_000e6, deployer, deployer);
        EwpgERC7540(erc7540Vault).fulfillDepositRequest(requestId);
    }

    function _registerStandardProducts() private {
        deploymentRegistry.setDeployment(keccak256("ERC-20"), erc20Token, keccak256("demo-erc20"));
        deploymentRegistry.setDeployment(keccak256("ERC-721"), erc721Token, keccak256("demo-erc721"));
        deploymentRegistry.setDeployment(keccak256("ERC-1155"), erc1155Token, keccak256("demo-erc1155"));
        deploymentRegistry.setDeployment(keccak256("ERC-3525"), erc3525Token, keccak256("demo-erc3525"));
        deploymentRegistry.setDeployment(keccak256("ERC-4626"), erc4626Vault, keccak256("demo-erc4626"));
        deploymentRegistry.setDeployment(keccak256("ERC-7540"), erc7540Vault, keccak256("demo-erc7540"));
    }

    function _writeManifest(address deployer, address greenMarket, address infraMarket) private {
        string memory output = "CHAIN_ID=11155111\n";
        output = string.concat(output, "OPERATOR_WALLET=", vm.toString(deployer), "\n");
        output = string.concat(output, "CUSTOMER_NORDBANK_WALLET=", vm.toString(NORDBANK), "\n");
        output = string.concat(output, "CUSTOMER_RHEINISCHE_WALLET=", vm.toString(RHEINISCHE), "\n");
        output = string.concat(output, "CUSTOMER_AURORA_WALLET=", vm.toString(AURORA), "\n");
        output = string.concat(output, "CUSTOMER_FRANKFURT_WALLET=", vm.toString(FRANKFURT), "\n");
        output = string.concat(output, "CUSTOMER_WUERTTEMBERG_WALLET=", vm.toString(WUERTTEMBERG), "\n");
        output = string.concat(output, "ASSET_TOKEN_FACTORY=", vm.toString(address(assetFactory)), "\n");
        output = string.concat(output, "DEPLOYMENT_REGISTRY=", vm.toString(address(deploymentRegistry)), "\n");
        output = string.concat(output, "ORG_REGISTRY=", vm.toString(address(orgRegistry)), "\n");
        output = string.concat(output, "PERMISSION_REGISTRY=", vm.toString(address(permissions)), "\n");
        output = string.concat(output, "PERMISSION_ORACLE=", vm.toString(address(permissionOracle)), "\n");
        output = string.concat(output, "DAPP_REGISTRY=", vm.toString(address(dappRegistry)), "\n");
        output = string.concat(output, "ECOSYSTEM_TIR=", vm.toString(address(trustedIssuers)), "\n");
        output = string.concat(output, "DEMO_ERC20_TOKEN=", vm.toString(erc20Token), "\n");
        output = string.concat(output, "DEMO_ERC721_TOKEN=", vm.toString(erc721Token), "\n");
        output = string.concat(output, "DEMO_ERC1155_TOKEN=", vm.toString(erc1155Token), "\n");
        output = string.concat(output, "DEMO_ERC3525_TOKEN=", vm.toString(erc3525Token), "\n");
        output = string.concat(output, "DEMO_ERC4626_VAULT=", vm.toString(erc4626Vault), "\n");
        output = string.concat(output, "DEMO_ERC7540_VAULT=", vm.toString(erc7540Vault), "\n");
        output = string.concat(output, "LOAN_TOKEN=", vm.toString(address(loanToken)), "\n");
        output = string.concat(output, "NAV_ORACLE=", vm.toString(address(navOracle)), "\n");
        output = string.concat(output, "GREEN_BOND_TOKEN=", vm.toString(address(greenBond)), "\n");
        output = string.concat(output, "GREEN_BOND_MARKET=", vm.toString(greenMarket), "\n");
        output = string.concat(output, "INFRA_NOTE_TOKEN=", vm.toString(address(infraNote)), "\n");
        output = string.concat(output, "INFRA_NOTE_MARKET=", vm.toString(infraMarket), "\n");
        vm.writeFile(vm.envOr("LOCAL_DEMO_OUTPUT", string("/output/demo.env")), output);

        string memory object = "registerwerk-demo";
        vm.serializeUint(object, "schemaVersion", 1);
        vm.serializeUint(object, "chainId", 11155111);
        vm.serializeAddress(object, "operatorWallet", deployer);
        vm.serializeAddress(object, "assetTokenFactory", address(assetFactory));
        vm.serializeAddress(object, "deploymentRegistry", address(deploymentRegistry));
        vm.serializeAddress(object, "orgRegistry", address(orgRegistry));
        vm.serializeAddress(object, "permissionRegistry", address(permissions));
        vm.serializeAddress(object, "permissionOracle", address(permissionOracle));
        vm.serializeAddress(object, "dappRegistry", address(dappRegistry));
        vm.serializeAddress(object, "ecosystemTrustedIssuersRegistry", address(trustedIssuers));
        vm.serializeAddress(object, "erc20", erc20Token);
        vm.serializeAddress(object, "erc721", erc721Token);
        vm.serializeAddress(object, "erc1155", erc1155Token);
        vm.serializeAddress(object, "erc3525", erc3525Token);
        vm.serializeAddress(object, "erc4626", erc4626Vault);
        vm.serializeAddress(object, "erc7540", erc7540Vault);
        vm.serializeAddress(object, "loanToken", address(loanToken));
        vm.serializeAddress(object, "greenBondMarket", greenMarket);
        string memory json = vm.serializeAddress(object, "infraNoteMarket", infraMarket);
        vm.writeJson(json, vm.envOr("LOCAL_DEMO_MANIFEST", string("/output/manifest.json")));
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
