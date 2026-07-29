// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/factory/AssetTokenFactory.sol";
import "../../src/tokens/EwpgERC20.sol";
import "../../src/tokens/EwpgERC721.sol";
import "../../src/tokens/EwpgERC1155.sol";
import "../../src/tokens/EwpgERC4626.sol";
import "../../src/tokens/EwpgERC7540.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @dev Minimal ERC-20 used as the underlying asset for vault deployment tests.
contract MockUnderlyingAsset is ERC20 {
    constructor() ERC20("Mock USD", "mUSD") {
        _mint(msg.sender, 1_000_000e18);
    }
}

contract AssetTokenFactoryTest is Test {
    AssetTokenFactory factory;
    MockUnderlyingAsset underlying;
    address registryWallet = makeAddr("registry");
    bytes32 constant ASSET_ID = keccak256("asset-factory-1");
    bytes32 constant ASSET_ID_2 = keccak256("asset-factory-2");

    function setUp() public {
        factory = new AssetTokenFactory(registryWallet);
        underlying = new MockUnderlyingAsset();
    }

    // -------------------------------------------------------------------------
    // Deployment
    // -------------------------------------------------------------------------

    function test_registryWalletIsSet() public view {
        assertEq(factory.registryWallet(), registryWallet);
    }

    function test_constructor_revertsForZeroRegistryWallet() public {
        vm.expectRevert("AssetTokenFactory: zero registry address");
        new AssetTokenFactory(address(0));
    }

    // -------------------------------------------------------------------------
    // deployToken — ERC-20 (type 0)
    // -------------------------------------------------------------------------

    function test_deployERC20_succeeds() public {
        address deployed = factory.deployToken(0, "My Token", "MTK", ASSET_ID);
        assertTrue(deployed != address(0));
        EwpgERC20 token = EwpgERC20(deployed);
        assertEq(token.name(), "My Token");
        assertEq(token.symbol(), "MTK");
        assertEq(token.assetId(), ASSET_ID);
        assertEq(token.registry(), registryWallet);
    }

    function test_deployERC20_emitsTokenDeployedEvent() public {
        vm.expectEmit(true, true, false, false);
        emit AssetTokenFactory.TokenDeployed(ASSET_ID, 0, address(0));
        factory.deployToken(0, "My Token", "MTK", ASSET_ID);
    }

    // -------------------------------------------------------------------------
    // deployToken — ERC-721 (type 1)
    // -------------------------------------------------------------------------

    function test_deployERC721_succeeds() public {
        address deployed = factory.deployToken(1, "My NFT", "MNFT", ASSET_ID);
        assertTrue(deployed != address(0));
        EwpgERC721 token = EwpgERC721(deployed);
        assertEq(token.name(), "My NFT");
        assertEq(token.symbol(), "MNFT");
        assertEq(token.assetId(), ASSET_ID);
        assertEq(token.registry(), registryWallet);
    }

    function test_deployERC721_emitsTokenDeployedEvent() public {
        vm.expectEmit(true, true, false, false);
        emit AssetTokenFactory.TokenDeployed(ASSET_ID, 1, address(0));
        factory.deployToken(1, "My NFT", "MNFT", ASSET_ID);
    }

    // -------------------------------------------------------------------------
    // deployToken — ERC-1155 (type 2)
    // -------------------------------------------------------------------------

    function test_deployERC1155_succeeds() public {
        address deployed = factory.deployToken(2, "", "MTKM", ASSET_ID);
        assertTrue(deployed != address(0));
        EwpgERC1155 token = EwpgERC1155(deployed);
        assertEq(token.symbol(), "MTKM");
        assertEq(token.assetId(), ASSET_ID);
        assertEq(token.registry(), registryWallet);
    }

    function test_deployERC1155_emitsTokenDeployedEvent() public {
        vm.expectEmit(true, true, false, false);
        emit AssetTokenFactory.TokenDeployed(ASSET_ID, 2, address(0));
        factory.deployToken(2, "", "MTKM", ASSET_ID);
    }

    // -------------------------------------------------------------------------
    // Unsupported token type
    // -------------------------------------------------------------------------

    function test_deployToken_revertsForUnsupportedType() public {
        // Type 3 (ERC-3525) is a supported deployToken branch; probe an out-of-range
        // type instead. 4/5 (vault types) are also unsupported here since they must
        // go through deployVault, but 6 is unambiguously out of range for both.
        vm.expectRevert(unicode"AssetTokenFactory: unsupported token type — for ERC-4626/7540 use deployVault");
        factory.deployToken(6, "X", "X", ASSET_ID);
    }

    // -------------------------------------------------------------------------
    // predictAddress — matches actual deployed address
    // -------------------------------------------------------------------------

    function test_predictAddress_matchesDeployedERC20() public {
        address predicted = factory.predictAddress(0, "My Token", "MTK", ASSET_ID, address(0));
        address deployed = factory.deployToken(0, "My Token", "MTK", ASSET_ID);
        assertEq(predicted, deployed);
    }

    function test_predictAddress_matchesDeployedERC721() public {
        address predicted = factory.predictAddress(1, "My NFT", "MNFT", ASSET_ID, address(0));
        address deployed = factory.deployToken(1, "My NFT", "MNFT", ASSET_ID);
        assertEq(predicted, deployed);
    }

    function test_predictAddress_matchesDeployedERC1155() public {
        address predicted = factory.predictAddress(2, "", "MTKM", ASSET_ID, address(0));
        address deployed = factory.deployToken(2, "", "MTKM", ASSET_ID);
        assertEq(predicted, deployed);
    }

    function test_predictAddress_revertsForUnsupportedType() public {
        // 5 (ERC-7540) is now a supported predictAddress branch (vaults); probe an
        // out-of-range type instead, matching test_deployToken_revertsForUnsupportedType.
        vm.expectRevert("AssetTokenFactory: unsupported token type");
        factory.predictAddress(6, "X", "X", ASSET_ID, address(0));
    }

    // -------------------------------------------------------------------------
    // CREATE2 determinism — same salt gives same address regardless of deploy order
    // -------------------------------------------------------------------------

    function test_create2_determinism_erc20() public {
        address predicted = factory.predictAddress(0, "Token A", "TKA", ASSET_ID, address(0));
        address deployed = factory.deployToken(0, "Token A", "TKA", ASSET_ID);
        assertEq(predicted, deployed);
    }

    function test_create2_differentAssetIds_giveDifferentAddresses() public {
        address addr1 = factory.deployToken(0, "Token A", "TKA", ASSET_ID);
        address addr2 = factory.deployToken(0, "Token A", "TKA", ASSET_ID_2);
        assertTrue(addr1 != addr2);
    }

    function test_create2_differentTokenTypes_giveDifferentAddresses() public {
        address erc20 = factory.deployToken(0, "Token A", "TKA", ASSET_ID);
        address erc721 = factory.deployToken(1, "Token A", "TKA", ASSET_ID);
        assertTrue(erc20 != erc721);
    }

    // -------------------------------------------------------------------------
    // Deployed token is functional (smoke test)
    // -------------------------------------------------------------------------

    function test_deployedERC20_isMintableByRegistry() public {
        address deployed = factory.deployToken(0, "My Token", "MTK", ASSET_ID);
        EwpgERC20 token = EwpgERC20(deployed);
        address alice = address(0xA);

        vm.startPrank(registryWallet);
        token.whitelist(alice);
        token.mint(alice, 100e18);
        vm.stopPrank();

        assertEq(token.balanceOf(alice), 100e18);
    }

    function test_deployedERC721_isMintableByRegistry() public {
        address deployed = factory.deployToken(1, "My NFT", "MNFT", ASSET_ID);
        EwpgERC721 token = EwpgERC721(deployed);
        address alice = address(0xA);

        vm.startPrank(registryWallet);
        token.whitelist(alice);
        token.mint(alice, 1);
        vm.stopPrank();

        assertEq(token.ownerOf(1), alice);
    }

    function test_deployedERC1155_isMintableByRegistry() public {
        address deployed = factory.deployToken(2, "", "MTKM", ASSET_ID);
        EwpgERC1155 token = EwpgERC1155(deployed);
        address alice = address(0xA);

        vm.startPrank(registryWallet);
        token.whitelist(alice);
        token.mint(alice, 1, 50, "");
        vm.stopPrank();

        assertEq(token.balanceOf(alice, 1), 50);
    }

    // -------------------------------------------------------------------------
    // deployVault — ERC-4626 (type 4)
    // -------------------------------------------------------------------------

    function test_deployVault_erc4626_succeeds() public {
        address deployed = factory.deployVault(4, "My Vault", "MVLT", ASSET_ID, address(underlying));
        assertTrue(deployed != address(0));
        EwpgERC4626 vault = EwpgERC4626(deployed);
        assertEq(vault.name(), "My Vault");
        assertEq(vault.symbol(), "MVLT");
        assertEq(vault.assetId(), ASSET_ID);
        assertEq(vault.registry(), registryWallet);
        assertEq(address(vault.asset()), address(underlying));
    }

    function test_deployVault_erc4626_emitsVaultDeployedEvent() public {
        vm.expectEmit(true, true, false, true);
        emit AssetTokenFactory.VaultDeployed(ASSET_ID, 4, address(0), address(underlying));
        factory.deployVault(4, "My Vault", "MVLT", ASSET_ID, address(underlying));
    }

    function test_deployVault_erc4626_isMintableByRegistry() public {
        address deployed = factory.deployVault(4, "My Vault", "MVLT", ASSET_ID, address(underlying));
        EwpgERC4626 vault = EwpgERC4626(deployed);
        address alice = address(0xA);
        underlying.transfer(alice, 1_000e18);

        vm.prank(registryWallet);
        vault.whitelist(alice);

        vm.startPrank(alice);
        underlying.approve(deployed, 1_000e18);
        vault.deposit(1_000e18, alice);
        vm.stopPrank();

        assertEq(vault.balanceOf(alice), 1_000e18);
    }

    // -------------------------------------------------------------------------
    // deployVault — ERC-7540 (type 5)
    // -------------------------------------------------------------------------

    function test_deployVault_erc7540_succeeds() public {
        address deployed = factory.deployVault(5, "My Async Vault", "MAVLT", ASSET_ID, address(underlying));
        assertTrue(deployed != address(0));
        EwpgERC7540 vault = EwpgERC7540(deployed);
        assertEq(vault.name(), "My Async Vault");
        assertEq(vault.symbol(), "MAVLT");
        assertEq(vault.assetId(), ASSET_ID);
        assertEq(vault.registry(), registryWallet);
        assertEq(address(vault.asset()), address(underlying));
    }

    function test_deployVault_erc7540_emitsVaultDeployedEvent() public {
        vm.expectEmit(true, true, false, true);
        emit AssetTokenFactory.VaultDeployed(ASSET_ID, 5, address(0), address(underlying));
        factory.deployVault(5, "My Async Vault", "MAVLT", ASSET_ID, address(underlying));
    }

    // -------------------------------------------------------------------------
    // deployVault — zero underlying asset revert
    // -------------------------------------------------------------------------

    function test_deployVault_revertsForZeroUnderlyingAsset() public {
        vm.expectRevert("AssetTokenFactory: zero underlying asset");
        factory.deployVault(4, "My Vault", "MVLT", ASSET_ID, address(0));
    }

    function test_deployVault_revertsForUnsupportedType() public {
        vm.expectRevert(unicode"AssetTokenFactory: unsupported vault type — use 4 (ERC4626) or 5 (ERC7540)");
        factory.deployVault(0, "My Vault", "MVLT", ASSET_ID, address(underlying));
    }

    // -------------------------------------------------------------------------
    // predictAddress — vault CREATE2 determinism
    // -------------------------------------------------------------------------

    function test_predictAddress_matchesDeployedERC4626() public {
        address predicted = factory.predictAddress(4, "My Vault", "MVLT", ASSET_ID, address(underlying));
        address deployed = factory.deployVault(4, "My Vault", "MVLT", ASSET_ID, address(underlying));
        assertEq(predicted, deployed);
    }

    function test_predictAddress_matchesDeployedERC7540() public {
        address predicted = factory.predictAddress(5, "My Async Vault", "MAVLT", ASSET_ID, address(underlying));
        address deployed = factory.deployVault(5, "My Async Vault", "MAVLT", ASSET_ID, address(underlying));
        assertEq(predicted, deployed);
    }

    function test_predictAddress_revertsForZeroUnderlyingAsset_vaultType() public {
        vm.expectRevert("AssetTokenFactory: zero underlying asset");
        factory.predictAddress(4, "My Vault", "MVLT", ASSET_ID, address(0));
    }
}
