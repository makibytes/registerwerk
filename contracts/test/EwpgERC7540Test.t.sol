// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC7540.sol";
import "../src/factory/AssetTokenFactory.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @dev Minimal USDC mock.
contract MockUSDC7540 is ERC20 {
    constructor() ERC20("USD Coin", "USDC") {
        _mint(msg.sender, 10_000_000e6);
    }
    function decimals() public pure override returns (uint8) { return 6; }
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
        (, , , bool pending) = vault.depositRequest(requestId);
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
        address vaultAddr = factory.deployVault(5, "Async Fund", "AF", ASSET_ID, address(usdc));
        EwpgERC7540 deployed = EwpgERC7540(vaultAddr);
        assertEq(deployed.assetId(), ASSET_ID);
    }
}
