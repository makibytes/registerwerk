// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../../src/lending/EwpgRepoMarket.sol";
import "../../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../../src/examples/MockStablecoin.sol";

/// @notice Bounded-random actor driving {EwpgRepoMarket} through its full lifecycle
///         (supply/withdraw/pledgeAndBorrow/repay/repayDebtOnly/claimCollateral/
///         claimLiquidationSurplus/liquidate/price moves) for
///         `EwpgRepoMarket.invariant.t.sol` .
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

    /// @dev The borrow is sized against the market's real origination cap at the current mark
    ///      (half to all of it) and the pool is topped up when short — otherwise nearly every call
    ///      reverts at the LTV/liquidity check, positions never get close to their liquidation
    ///      threshold, and {liquidate} (the part of the market the invariants most need to cover)
    ///      is never reached.
    function pledgeAndBorrow(uint256 borrowerSeed, uint256 collateralAmount, uint256 borrowAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        // A quarter of the positions are a single indivisible unit worth more than their debt once
        // the mark drops — the whole-unit liquidation regime (surplus to the borrower).
        collateralAmount = collateralAmount % 4 == 0 ? 1 : bound(collateralAmount, 1, 500);
        (uint256 price,) = navOracle.price(address(collateralToken));
        uint256 maxBorrow = collateralAmount * price * market.maxLtvBps() / 10_000;
        borrowAmount = bound(borrowAmount, maxBorrow / 2 + 1, maxBorrow);
        if (market.availableLiquidity() < borrowAmount) {
            address funder = _lender(borrowerSeed);
            loanToken.mint(funder, borrowAmount);
            vm.startPrank(funder);
            loanToken.approve(address(market), type(uint256).max);
            try market.supply(borrowAmount) {} catch {}
            vm.stopPrank();
        }
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

    function repayDebtOnly(uint256 borrowerSeed, uint256 repayAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        uint256 debt = market.debtOf(borrower);
        if (debt == 0) return;
        repayAmount = bound(repayAmount, 1, debt);
        loanToken.mint(borrower, repayAmount);
        vm.startPrank(borrower);
        loanToken.approve(address(market), type(uint256).max);
        try market.repayDebtOnly(repayAmount) {} catch {}
        vm.stopPrank();
    }

    /// @dev Zero-debt positions with credited collateral arise from {repayDebtOnly} and from
    ///      full-close liquidations; this is the only way that collateral leaves custody.
    function claimCollateral(uint256 borrowerSeed) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        vm.prank(borrower);
        try market.claimCollateral() {} catch {}
    }

    /// @dev Liquidation surplus (whole-unit rounding above the closed debt) is owed cash.
    function claimLiquidationSurplus(uint256 borrowerSeed) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        vm.prank(borrower);
        try market.claimLiquidationSurplus() {} catch {}
    }

    /// @dev Largest amount by which a successful {liquidate} charged its caller above the
    ///      `maxRepayAmount` it passed (H2). Must stay zero — see
    ///      `invariant_liquidatorNeverPaysMoreThanMaxRepay`.
    uint256 public maxLiquidatorOverpay;
    /// @dev Number of successful liquidations whose liquidator discount (collateral value received
    ///      less cash paid) differed from `bonus × debt closed` by more than rounding (H2).
    uint256 public bonusMispricedCount;
    /// @dev Successful liquidations, and how many of them sold a unit worth more than the debt it
    ///      closed (the whole-unit surplus regime) — evidence that the run exercised H2 at all.
    uint256 public liquidationCount;
    uint256 public surplusLiquidationCount;

    function liquidate(uint256 borrowerSeed, uint256 liquidatorSeed, uint256 maxRepayAmount) public {
        address borrower = borrowers[borrowerSeed % borrowers.length];
        uint256 debt = market.debtOf(borrower);
        if (debt == 0) return;
        address liquidatorAddr = _liquidator(liquidatorSeed);
        (uint256 price,) = navOracle.price(address(collateralToken));
        // The payment can exceed the debt closed by up to one unit's value (the unit is sold whole
        // and the rest is the borrower's surplus), so liquidators authorise up to `debt + price`.
        maxRepayAmount = maxRepayAmount % 3 == 0 ? debt + price : bound(maxRepayAmount, 1, debt + price);
        // Half of the calls fund the liquidator with exactly `maxRepayAmount`; the other half give
        // it slack so that an overcharge cannot hide behind a failed transfer (the previous
        // `maxRepayAmount + price` funding made a one-unit overpay invisible) — the spend is
        // measured below and any excess over `maxRepayAmount` trips the invariant.
        uint256 funding = liquidatorSeed % 2 == 0 ? maxRepayAmount : 2 * maxRepayAmount + price;
        deal(address(loanToken), liquidatorAddr, funding, true);
        vm.startPrank(liquidatorAddr);
        loanToken.approve(address(market), type(uint256).max);
        try market.liquidate(borrower, maxRepayAmount) returns (uint256 repaid, uint256 seized) {
            uint256 spent = funding - loanToken.balanceOf(liquidatorAddr);
            if (spent > maxRepayAmount && spent - maxRepayAmount > maxLiquidatorOverpay) {
                maxLiquidatorOverpay = spent - maxRepayAmount;
            }
            uint256 discount = seized * price - spent;
            uint256 expectedBonus = repaid * market.liquidationBonusBps() / 10_000;
            uint256 diff = discount > expectedBonus ? discount - expectedBonus : expectedBonus - discount;
            if (diff > 2) bonusMispricedCount++;
            liquidationCount++;
            if (spent > repaid) surplusLiquidationCount++;
        } catch {}
        vm.stopPrank();
    }

    /// @dev Price moves are bounded to the oracle's own ordinary deviation tolerance so the
    ///      handler never needs the override-permissioned path — a random walk within normal
    ///      operating conditions is the scenario these invariants are meant to hold under.
    function pushPrice(uint256 direction, uint256 magnitudeBps) public {
        magnitudeBps = bound(magnitudeBps, 0, 1500); // the oracle rejects moves beyond its 1500bps window cap
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

    /// @dev Fully-levered borrow, an ordinary-cap price drop, then a liquidation — the sequence a
    ///      purely random walk over the other actions almost never produces within 20 calls, so
    ///      without it the invariants would hold vacuously over {liquidate}.
    function leveragedBorrowCrashAndLiquidate(
        uint256 borrowerSeed,
        uint256 liquidatorSeed,
        uint256 collateralAmount,
        uint256 crashBps,
        uint256 maxRepayAmount
    ) external {
        pledgeAndBorrow(borrowerSeed, collateralAmount, type(uint256).max);
        pushPrice(1, crashBps);
        liquidate(borrowerSeed, liquidatorSeed, maxRepayAmount);
    }

    function borrowerCount() external view returns (uint256) {
        return borrowers.length;
    }

    function lenderCount() external view returns (uint256) {
        return lendersSeen.length;
    }
}
