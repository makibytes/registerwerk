// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC4626.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/factory/AssetTokenFactoryBootstrap.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @dev Simple ERC-20 stablecoin for testing.
contract MockUSDC is ERC20 {
    constructor() ERC20("USD Coin", "USDC") {
        _mint(msg.sender, 1_000_000e6);
    }

    function decimals() public pure override returns (uint8) {
        return 6;
    }
}

contract EwpgERC4626Test is Test {
    EwpgERC4626 vault;
    MockUSDC usdc;
    AssetTokenFactory factory;

    address registry = makeAddr("registry");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");

    bytes32 constant ASSET_ID = keccak256("fund-asset-uuid");

    function setUp() public {
        usdc = new MockUSDC();
        usdc.transfer(alice, 100_000e6);
        usdc.transfer(bob, 100_000e6);

        vm.startPrank(registry);
        vault = new EwpgERC4626(usdc, "Registerwerk Bond Fund A", "RWBFA", registry, ASSET_ID);
        vault.whitelist(alice);
        vault.whitelist(bob);
        vm.stopPrank();
    }

    // ── NAV strike ─────────────────────────────────────────────────────────────

    function test_setNavPerShare_updatesConversion() public {
        // 1:1 before strike
        assertEq(vault.convertToShares(1000e6), 1000e6);

        // Strike NAV at 1.05 (1.05 * 1e18)
        uint256 nav = 1.05e18;
        vm.prank(registry);
        vault.setNavPerShare(nav, block.timestamp, bytes32(0));

        // 1000 assets → ~952.38 shares (1000 / 1.05)
        uint256 shares = vault.convertToShares(1000e6);
        assertApproxEqRel(shares, 952e6, 0.01e18); // within 1%
    }

    function test_setNavPerShare_revertsWithZero() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC4626: NAV must be positive");
        vault.setNavPerShare(0, block.timestamp, bytes32(0));
    }

    function test_setNavPerShare_emitsNavStruck() public {
        uint256 nav = 1.05e18;
        vm.prank(registry);
        vm.expectEmit(true, false, false, true);
        emit EwpgERC4626.NavStruck(1, nav, block.timestamp, bytes32(0));
        vault.setNavPerShare(nav, block.timestamp, bytes32(0));
    }

    // ── Deposit cap ─────────────────────────────────────────────────────────────

    function test_depositCap_limitsMaxDeposit() public {
        vm.prank(registry);
        vault.setDepositCap(50_000e6);
        assertEq(vault.maxDeposit(alice), 50_000e6);

        // Deposit 40k
        vm.startPrank(alice);
        usdc.approve(address(vault), 40_000e6);
        vault.deposit(40_000e6, alice);
        vm.stopPrank();

        // Max deposit should now be 10k
        assertEq(vault.maxDeposit(alice), 10_000e6);
    }

    // ── Compliance ─────────────────────────────────────────────────────────────

    function test_transfer_blockedWhenPaused() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        vault.deposit(10_000e6, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.pause();

        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: transfers are paused");
        vault.transfer(bob, 100);
    }

    function test_forcedTransfer_bypassesCompliance() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        vault.deposit(10_000e6, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.pause();

        uint256 aliceShares = vault.balanceOf(alice);
        vm.prank(registry);
        vault.forcedTransfer(alice, bob, aliceShares / 2, unicode"BaFin §24");
        assertEq(vault.balanceOf(bob), aliceShares / 2);
    }

    // ── Factory dispatch ──────────────────────────────────────────────────────

    function test_factory_deploysErc4626ViaDeployVault() public {
        factory = new AssetTokenFactory(registry);
        vm.startPrank(registry);
        AssetTokenFactoryBootstrap.configure(factory, registry);
        vm.stopPrank();
        address vaultAddr = factory.deployVault(4, "Fund A", "FA", ASSET_ID, address(usdc));
        assertFalse(vaultAddr == address(0));
        EwpgERC4626 deployed = EwpgERC4626(vaultAddr);
        assertEq(deployed.assetId(), ASSET_ID);
        assertEq(deployed.asset(), address(usdc));
    }

    function test_factory_revertsWhenDeployingVaultTypeViaDeployToken() public {
        factory = new AssetTokenFactory(registry);
        vm.startPrank(registry);
        AssetTokenFactoryBootstrap.configure(factory, registry);
        vm.stopPrank();
        vm.expectRevert();
        factory.deployToken(4, "Fund", "F", ASSET_ID);
    }

    // ── Regression: NAV must drive ACTUAL flows, not just views ───────────────

    function test_depositMintsSharesAtStruckNav() public {
        vm.prank(registry);
        vault.setNavPerShare(2e18, block.timestamp, bytes32(0)); // NAV = 2.0

        vm.startPrank(alice);
        usdc.approve(address(vault), 1000e6);
        uint256 shares = vault.deposit(1000e6, alice);
        vm.stopPrank();

        // 1000 assets at NAV 2.0 -> 500 shares. Before the fix, deposit() used the
        // naive totalAssets/totalSupply ratio (1:1 on first deposit) -> 1000 shares.
        assertEq(shares, 500e6, "deposit must settle at struck NAV");
        assertEq(vault.balanceOf(alice), 500e6);
    }

    function test_donationDoesNotChangeConversionRate() public {
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        uint256 before = vault.previewDeposit(1000e6);
        // Classic ERC-4626 inflation attack: donate underlying directly to the vault
        vm.prank(bob);
        usdc.transfer(address(vault), 50_000e6);

        assertEq(vault.previewDeposit(1000e6), before, "NAV conversion must be donation-proof");
    }

    function test_depositToNonWhitelistedReverts() public {
        address mallory = makeAddr("mallory");
        usdc.transfer(mallory, 1000e6);
        vm.startPrank(mallory);
        usdc.approve(address(vault), 1000e6);
        vm.expectRevert("EwpgERC4626: recipient not whitelisted");
        vault.deposit(1000e6, mallory);
        vm.stopPrank();
    }

    function test_depositCapNotBypassableViaMint() public {
        vm.startPrank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vault.setDepositCap(1000e6);
        vm.stopPrank();

        // maxMint must mirror the remaining asset capacity
        assertEq(vault.maxMint(alice), 1000e6, "maxMint must reflect deposit cap");

        vm.startPrank(alice);
        usdc.approve(address(vault), 2000e6);
        vm.expectRevert(); // ERC4626ExceededMaxMint
        vault.mint(1001e6, alice);
        vm.stopPrank();
    }
}
