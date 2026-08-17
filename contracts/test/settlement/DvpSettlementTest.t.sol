// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/settlement/DvpSettlement.sol";
import "../../src/examples/MockStablecoin.sol";

/// @notice Tests for the ERC-7573-style same-chain DvP rail: one leg is escrowed, the
///         counterparty settles both legs atomically, expiry refunds the locker. A plain
///         ERC-20 stands in for the asset leg; see the DvpSettlement NatSpec for how
///         ERC-3643 assets are handled (lock the payment leg instead).
contract DvpSettlementTest is Test {
    DvpSettlement dvp;
    MockStablecoin asset; // security-token leg stand-in
    MockStablecoin cash; // MiCAR EMT payment leg (6 decimals)

    address operator = address(0x10);
    address seller = address(0x11);
    address buyer = address(0x22);
    address stranger = address(0x33);

    bytes32 constant TRADE = keccak256("trade-1");
    uint256 constant ASSET_AMOUNT = 1_000;
    uint256 constant PAYMENT_AMOUNT = 100_000e6;
    uint64 expiry;

    function setUp() public {
        dvp = new DvpSettlement(operator);
        asset = new MockStablecoin("Demo Bond Units", "BOND", 0);
        cash = new MockStablecoin("USD Coin", "USDC", 6);
        expiry = uint64(block.timestamp + 1 days);

        asset.mint(seller, ASSET_AMOUNT);
        cash.mint(buyer, PAYMENT_AMOUNT);

        vm.prank(seller);
        asset.approve(address(dvp), type(uint256).max);
        vm.prank(buyer);
        cash.approve(address(dvp), type(uint256).max);
    }

    function _lockAsset() private {
        vm.prank(seller);
        dvp.lockAsset(TRADE, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function _lockPayment() private {
        vm.prank(buyer);
        dvp.lockPayment(TRADE, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    // ── asset-leg lock ────────────────────────────────────────────────────────

    function test_lockAsset_escrowsAssetLeg() public {
        _lockAsset();
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
        assertEq(asset.balanceOf(seller), 0);
    }

    function test_settle_afterLockAsset_swapsBothLegsAtomically() public {
        _lockAsset();

        vm.prank(buyer);
        dvp.settle(TRADE);

        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(cash.balanceOf(seller), PAYMENT_AMOUNT);
        assertEq(asset.balanceOf(address(dvp)), 0, "no residue in escrow");
        assertEq(cash.balanceOf(address(dvp)), 0);
    }

    function test_settle_revertsForNonBuyer() public {
        _lockAsset();
        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotCounterparty.selector, TRADE, stranger));
        dvp.settle(TRADE);
    }

    function test_settle_revertsWithoutBuyerFunds() public {
        _lockAsset();
        vm.prank(buyer);
        cash.transfer(stranger, PAYMENT_AMOUNT); // buyer can no longer pay

        vm.prank(buyer);
        vm.expectRevert();
        dvp.settle(TRADE);

        // Escrow stays intact — the seller has not lost the asset leg.
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
    }

    function test_settle_revertsOnDoubleSettle() public {
        _lockAsset();
        vm.startPrank(buyer);
        dvp.settle(TRADE);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.settle(TRADE);
        vm.stopPrank();
    }

    function test_settle_revertsAfterExpiry() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeExpired.selector, TRADE, expiry));
        dvp.settle(TRADE);
    }

    // ── payment-leg lock (the ERC-3643-friendly direction) ───────────────────

    function test_lockPayment_thenSellerSettles() public {
        _lockPayment();
        assertEq(cash.balanceOf(address(dvp)), PAYMENT_AMOUNT);

        vm.prank(seller);
        dvp.settle(TRADE);

        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(cash.balanceOf(seller), PAYMENT_AMOUNT);
        assertEq(cash.balanceOf(address(dvp)), 0);
    }

    function test_settle_afterLockPayment_revertsForBuyer() public {
        _lockPayment();
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotCounterparty.selector, TRADE, buyer));
        dvp.settle(TRADE);
    }

    // ── cancellation / expiry refunds ─────────────────────────────────────────

    function test_cancel_byLockerAfterExpiry_refunds() public {
        _lockAsset();
        vm.warp(expiry);

        vm.prank(seller);
        dvp.cancel(TRADE);

        assertEq(asset.balanceOf(seller), ASSET_AMOUNT);
        assertEq(asset.balanceOf(address(dvp)), 0);
    }

    function test_cancel_byLockerBeforeExpiry_reverts() public {
        _lockAsset();
        vm.prank(seller);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotExpired.selector, TRADE, expiry));
        dvp.cancel(TRADE);
    }

    function test_cancel_byCounterpartyAnyTime_refundsLocker() public {
        _lockPayment();

        vm.prank(seller); // counterparty of the locked payment leg renounces early
        dvp.cancel(TRADE);

        assertEq(cash.balanceOf(buyer), PAYMENT_AMOUNT);
    }

    function test_cancel_byStranger_reverts() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotTradeParty.selector, TRADE, stranger));
        dvp.cancel(TRADE);
    }

    function test_settle_revertsAfterCancel() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(seller);
        dvp.cancel(TRADE);

        vm.warp(expiry - 1); // even if time could rewind, state is terminal
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.settle(TRADE);
    }

    // ── validation ────────────────────────────────────────────────────────────

    function test_lock_rejectsDuplicateTradeId() public {
        _lockAsset();
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeAlreadyExists.selector, TRADE));
        dvp.lockPayment(TRADE, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function test_lock_rejectsInvalidParameters() public {
        vm.startPrank(seller);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(TRADE, address(0), asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(TRADE, buyer, asset, 0, cash, PAYMENT_AMOUNT, expiry);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(TRADE, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, uint64(block.timestamp));
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(TRADE, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry); // self-trade
        vm.stopPrank();
    }

    // ── pause ────────────────────────────────────────────────────

    function test_pause_blocksNewLocksAndSettlement() public {
        vm.prank(operator);
        dvp.pause();
        assertTrue(dvp.paused());

        vm.prank(seller);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.lockAsset(TRADE, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);

        vm.prank(buyer);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.lockPayment(TRADE, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function test_pause_stillAllowsSettleAndCancelOfAlreadyLockedTrades() public {
        _lockAsset(); // locked before the pause

        vm.prank(operator);
        dvp.pause();

        // settle() IS gated — no new fund movement while paused...
        vm.prank(buyer);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.settle(TRADE);

        // ...but cancel() must never trap an already-escrowed leg.
        vm.warp(expiry);
        vm.prank(seller);
        dvp.cancel(TRADE);
        assertEq(asset.balanceOf(seller), ASSET_AMOUNT, "locker must be able to reclaim escrow even while paused");
    }

    function test_pause_revertsForNonOperator() public {
        vm.prank(stranger);
        vm.expectRevert();
        dvp.pause();
    }

    function test_unpause_resumesNormalOperation() public {
        vm.startPrank(operator);
        dvp.pause();
        dvp.unpause();
        vm.stopPrank();

        assertFalse(dvp.paused());
        _lockAsset(); // succeeds — no revert
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
    }
}
