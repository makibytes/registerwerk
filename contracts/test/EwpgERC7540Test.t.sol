// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC7540.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/factory/AssetTokenFactoryBootstrap.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @dev Minimal USDC mock.
contract MockUSDC7540 is ERC20 {
    constructor() ERC20("USD Coin", "USDC") {
        _mint(msg.sender, 10_000_000e6);
    }

    function decimals() public pure override returns (uint8) {
        return 6;
    }
}

contract EwpgERC7540Test is Test {
    EwpgERC7540 vault;
    MockUSDC7540 usdc;
    AssetTokenFactory factory;

    address registry = makeAddr("registry");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");

    bytes32 constant ASSET_ID = keccak256("async-vault-uuid");

    function setUp() public {
        usdc = new MockUSDC7540();
        usdc.transfer(alice, 200_000e6);
        usdc.transfer(bob, 200_000e6);

        vm.startPrank(registry);
        vault = new EwpgERC7540(usdc, "Registerwerk Async Fund", "RWAF", registry, ASSET_ID);
        vault.whitelist(alice);
        vault.whitelist(bob);
        vm.stopPrank();
    }

    // ── Deposit request / fulfill ─────────────────────────────────────────────

    function test_requestDeposit_createsRequest() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        (uint256 assets, address controller, address owner, bool pending) = vault.depositRequest(requestId);
        assertEq(assets, 10_000e6);
        assertEq(controller, alice);
        assertEq(owner, alice);
        assertTrue(pending);
    }

    function test_fulfillDepositRequest_mintsShares() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        // Strike NAV first
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);

        assertGt(vault.balanceOf(alice), 0);
        (,,, bool pending) = vault.depositRequest(requestId);
        assertFalse(pending);
    }

    function test_fulfillDepositRequest_revertsIfAlreadyFulfilled() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 5_000e6);
        uint256 requestId = vault.requestDeposit(5_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: not pending");
        vault.fulfillDepositRequest(requestId);
    }

    // ── Settlement delay ──────────────────────────────────────────────────────

    function test_settlementDelay_preventsEarlyFulfillment() public {
        vm.prank(registry);
        vault.setMinSettlementDelay(3600); // 1 hour

        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: settlement delay not elapsed");
        vault.fulfillDepositRequest(requestId);

        // Advance time
        vm.warp(block.timestamp + 3601);
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);
        assertGt(vault.balanceOf(alice), 0);
    }

    // ── Redeem request / fulfill ──────────────────────────────────────────────

    function test_requestRedeem_thenFulfill_returnsAssets() public {
        // First deposit to get shares
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 depReqId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.fulfillDepositRequest(depReqId);

        uint256 shares = vault.balanceOf(alice);
        assertGt(shares, 0);

        // Now redeem
        vm.startPrank(alice);
        vault.approve(address(vault), shares);
        uint256 redeemReqId = vault.requestRedeem(shares, alice, alice);
        vm.stopPrank();

        uint256 usdcBefore = usdc.balanceOf(alice);
        vm.prank(registry);
        vault.fulfillRedeemRequest(redeemReqId);
        uint256 usdcAfter = usdc.balanceOf(alice);
        assertGt(usdcAfter, usdcBefore);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    function test_cancelDepositRequest_returnsAssets() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 5_000e6);
        uint256 requestId = vault.requestDeposit(5_000e6, alice, alice);
        vm.stopPrank();

        uint256 usdcBefore = usdc.balanceOf(alice);
        vm.prank(alice);
        vault.cancelDepositRequest(requestId);
        assertEq(usdc.balanceOf(alice), usdcBefore + 5_000e6);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    function test_factory_deploysErc7540ViaDeployVault() public {
        factory = new AssetTokenFactory(registry);
        vm.startPrank(registry);
        AssetTokenFactoryBootstrap.configure(factory, registry);
        vm.stopPrank();
        address vaultAddr = factory.deployVault(5, "Async Fund", "AF", ASSET_ID, address(usdc));
        EwpgERC7540 deployed = EwpgERC7540(vaultAddr);
        assertEq(deployed.assetId(), ASSET_ID);
    }

    // ── Regression: redemption authorization, NAV requirement, self-custody ───

    function test_strangerCannotRequestRedeemForOthers() public {
        _depositFor(alice, 1000e6);

        address mallory = makeAddr("mallory");
        vm.prank(mallory);
        vm.expectRevert(); // ERC20InsufficientAllowance
        vault.requestRedeem(100e6, mallory, alice);
    }

    function test_approvedOperatorCanRequestRedeem() public {
        _depositFor(alice, 1000e6);
        uint256 aliceShares = vault.balanceOf(alice);

        address operator = makeAddr("operator");
        vm.prank(alice);
        vault.approve(operator, aliceShares);

        vm.prank(operator);
        uint256 requestId = vault.requestRedeem(aliceShares, alice, alice);

        // Shares moved into vault self-custody (exempt from whitelist)
        assertEq(vault.balanceOf(alice), 0);
        assertEq(vault.balanceOf(address(vault)), aliceShares);
        (,, address owner, bool pending) = vault.redeemRequest(requestId);
        assertEq(owner, alice);
        assertTrue(pending);
    }

    function test_fulfillWithoutNavStrikeReverts() public {
        // No NAV ever struck — fulfilling a deposit at the implicit 1:1
        // pre-strike rate must be impossible for a regulated fund.
        vm.startPrank(alice);
        usdc.approve(address(vault), 1000e6);
        uint256 requestId = vault.requestDeposit(1000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: NAV not struck");
        vault.fulfillDepositRequest(requestId);
    }

    function test_synchronousErc4626EntryPointsRevert() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), type(uint256).max);

        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.deposit(1000e6, alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.mint(1000e6, alice);
        vm.stopPrank();

        _depositFor(alice, 1000e6);

        vm.startPrank(alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.withdraw(1, alice, alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.redeem(1, alice, alice);
        vm.stopPrank();
    }

    function test_synchronousErc4626MaximumsAreZero() public view {
        assertEq(vault.maxDeposit(alice), 0);
        assertEq(vault.maxMint(alice), 0);
        assertEq(vault.maxWithdraw(alice), 0);
        assertEq(vault.maxRedeem(alice), 0);
    }

    // ── Helpers for regression tests ──────────────────────────────────────────

    function _depositFor(address investor, uint256 assets) internal {
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.startPrank(investor);
        usdc.approve(address(vault), assets);
        uint256 requestId = vault.requestDeposit(assets, investor, investor);
        vm.stopPrank();
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);
    }
}
