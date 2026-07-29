// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../../src/lending/EwpgRepoMarket.sol";
import "../../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../../src/examples/MockStablecoin.sol";

/// @notice Bounded-random actor driving {EwpgRepoMarket} through its full lifecycle
///         (supply/withdraw/pledgeAndBorrow/repay/liquidate/price moves) for
///         `EwpgRepoMarket.invariant.t.sol` (finding #12, Phase 7).
///
/// @dev Borrowers are a small FIXED set pre-authorized (KYC + `repo-facility.borrow`) by the
///      invariant test's `setUp()` — replicating the full org-registration dance for
///      arbitrarily-fuzzed addresses would dominate this handler's complexity for no real
///      invariant-strength benefit, since the gating itself is already covered by
///      `EwpgRepoMarket.t.sol`'s dedicated gating tests. Lenders/liquidators are genuinely
///      unbounded fuzzed addresses, matching their real ungated design.
contract EwpgRepoMarketHandler is Test {
    EwpgRepoMarket public market;
    RegisterwerkNavOracle public navOracle;
    MockStablecoin public loanToken;
    MockStablecoin public collateralToken;
    address public pricePusher;
    address[] public borrowers;

    address[] public lendersSeen;
    mapping(address => bool) public isKnownLender;

    uint256 public constant INITIAL_PRICE = 100e6;
    uint256 public currentPrice = INITIAL_PRICE;

    constructor(
        EwpgRepoMarket market_,
        RegisterwerkNavOracle navOracle_,
        MockStablecoin loanToken_,
        MockStablecoin collateralToken_,
        address pricePusher_,
        address[] memory borrowers_
    ) {
        market = market_;
        navOracle = navOracle_;
        loanToken = loanToken_;
        collateralToken = collateralToken_;
        pricePusher = pricePusher_;
        borrowers = borrowers_;
    }

    function _lender(uint256 seed) private returns (address lender) {
        lender = address(uint160(uint256(keccak256(abi.encode("lender", seed % 8)))));
        if (!isKnownLender[lender]) {
            isKnownLender[lender] = true;
            lendersSeen.push(lender);
        }
    }

    function _liquidator(uint256 seed) private pure returns (address) {
        return address(uint160(uint256(keccak256(abi.encode("liquidator", seed % 4)))));
    }

    function supply(uint256 lenderSeed, uint256 amount) public {
        address lender = _lender(lenderSeed);
        amount = bound(amount, 1e6, 200_000e6);
        loanToken.mint(lender, amount);
        vm.startPrank(lender);
        loanToken.approve(address(market), type(uint256).max);
        try market.supply(amount) {} catch {}
        vm.stopPrank();
    }

    function withdraw(uint256 lenderSeed, uint256 amount) public {
        if (lendersSeen.length == 0) return;
        address lender = lendersSeen[lenderSeed % lendersSeen.length];
        uint256 claim = market.balanceOf(lender);
        if (claim == 0) return;
        amount = bound(amount, 1, claim);
        vm.prank(lender);
        try market.withdraw(amount) {} catch {}
    }

    function pledgeAndBorrow(uint256 borrowerSeed, uint256 collateralAmount, uint256 borrowAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        collateralAmount = bound(collateralAmount, 1, 500);
        borrowAmount = bound(borrowAmount, 1, 100_000e6);
        collateralToken.mint(borrower, collateralAmount);
        vm.startPrank(borrower);
        collateralToken.approve(address(market), type(uint256).max);
        loanToken.approve(address(market), type(uint256).max);
        try market.pledgeAndBorrow(collateralAmount, borrowAmount) {} catch {}
        vm.stopPrank();
    }

    function repay(uint256 borrowerSeed, uint256 repayAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        uint256 debt = market.debtOf(borrower);
        if (debt == 0) return;
        repayAmount = bound(repayAmount, 1, debt);
        loanToken.mint(borrower, repayAmount);
        vm.startPrank(borrower);
        loanToken.approve(address(market), type(uint256).max);
        try market.repay(repayAmount) {} catch {}
        vm.stopPrank();
    }

    function liquidate(uint256 borrowerSeed, uint256 liquidatorSeed, uint256 maxRepayAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        uint256 debt = market.debtOf(borrower);
        if (debt == 0) return;
        address liquidatorAddr = _liquidator(liquidatorSeed);
        maxRepayAmount = bound(maxRepayAmount, 1, debt);
        loanToken.mint(liquidatorAddr, maxRepayAmount);
        vm.startPrank(liquidatorAddr);
        loanToken.approve(address(market), type(uint256).max);
        try market.liquidate(borrower, maxRepayAmount) {} catch {}
        vm.stopPrank();
    }

    /// @dev Price moves are bounded to the oracle's own ordinary deviation tolerance so the
    ///      handler never needs the override-permissioned path — a random walk within normal
    ///      operating conditions is the scenario these invariants are meant to hold under.
    function pushPrice(uint256 direction, uint256 magnitudeBps) public {
        magnitudeBps = bound(magnitudeBps, 0, 1500); // stay under the oracle's 2000bps cap
        uint256 newPrice = direction % 2 == 0
            ? currentPrice + (currentPrice * magnitudeBps) / 10_000
            : currentPrice - (currentPrice * magnitudeBps) / 10_000;
        if (newPrice == 0) return;
        vm.prank(pricePusher);
        try navOracle.pushPrice(address(collateralToken), newPrice) {
            currentPrice = newPrice;
        } catch {}
    }

    function warp(uint256 secondsElapsed) public {
        secondsElapsed = bound(secondsElapsed, 0, 30 days);
        vm.warp(block.timestamp + secondsElapsed);
    }

    function borrowerCount() external view returns (uint256) {
        return borrowers.length;
    }

    function lenderCount() external view returns (uint256) {
        return lendersSeen.length;
    }
}
