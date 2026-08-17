// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";
import "./oracle/IRepoOracle.sol";

/// @title EwpgRepoMarket
/// @notice Isolated collateralized-lending market for exactly ONE {loanToken, collateralToken}
///         pair — the Morpho-Blue-style evolution of `EwpgRepoFacility` (see that contract's
///         NatSpec for the full repo/money-market design rationale, unchanged here). Where the
///         facility pools every collateral type behind one shared cash pool and one shared
///         index pair — so a bad mark on any single collateral affects every lender in the
///         facility — each `EwpgRepoMarket` instance isolates risk to its own pair: its own
///         cash, its own `liquidityIndex`/`borrowIndex`, its own LLTV and rate curve. Listing a
///         new security is deploying another market via `EwpgRepoMarketFactory`, never a vote
///         on a shared pool's risk parameters.
///
///         Same asymmetric gating as `EwpgRepoFacility`: the lender side ({supply}/{withdraw})
///         is open to any stablecoin holder, the borrower side ({pledgeAndBorrow}) requires
///         `repo-facility.borrow` plus a KYC claim, and {repay}/{liquidate} are intentionally
///         ungated at this layer — the collateral token's own T-REX identity-registry check is
///         the real, sufficient compliance gate on any transfer out.
///
/// @dev A handful of values fixed forever at construction, mirroring Morpho Blue's "a market is
///      just its immutable parameters": {loanToken}, {collateralToken}, {maxLtvBps}, {lltvBps},
///      {priceOracle}, and the rate-curve constants {baseRateWad}/{slopeWad}. None of these can
///      change after deployment — a different curve or LTV is a different market, not a
///      parameter update, so lenders always know exactly what risk a given market carries.
///      `liquidationBonusBps` is tracked as an explicit immutable too (a deliberate, auditable
///      deviation from Morpho Blue's LLTV-derived incentive formula, capped at
///      {MAX_LIQUIDATION_BONUS_BPS}), and {reserveFactorBps} is the one operator-mutable
///      economics knob (capped at {MAX_RESERVE_FACTOR_BPS}), matching Morpho Blue's own
///      owner-settable per-market fee.
///
///      Unlike a single shared Morpho Blue LLTV, {maxLtvBps} (the origination cap) and
///      {lltvBps} (the liquidation threshold) are deliberately separate: a borrower who draws
///      this market's own quoted maximum must not become immediately
///      liquidatable the moment any interest accrues — restoring the buffer `EwpgRepoFacility`
///      always enforced (`maxLtvBps < liquidationThresholdBps`).
contract EwpgRepoMarket is RegisterwerkGated, ReentrancyGuard {
    using SafeERC20 for IERC20;

    bytes32 public constant BORROW = keccak256("repo-facility.borrow");
    bytes32 public constant CONFIGURE = keccak256("repo-facility.configure");
    uint256 public constant TOPIC_KYC = 1;

    uint256 private constant WAD = 1e18;
    uint256 private constant BPS_DENOMINATOR = 10_000;
    uint256 private constant SECONDS_PER_YEAR = 365 days;

    /// @notice Fraction of an unhealthy-but-not-deeply-underwater position's outstanding debt a
    ///         single {liquidate} call may close. Repeated calls — the position remains
    ///         liquidatable until its health factor recovers above 1e18 — can fully unwind it,
    ///         the same partial-liquidation model Aave uses to bound a single liquidator's
    ///         required capital, refining `EwpgRepoFacility`'s full-close-factor-only
    ///         liquidation. Once health factor drops below
    ///         {FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD}, {MAX_CLOSE_FACTOR_BPS} applies instead —
    ///         a severely underwater position no longer needs the gradual unwind this constant
    ///         protects against; on the contrary, the position should close as fast as possible
    ///         to cap further loss.
    uint256 public constant CLOSE_FACTOR_BPS = 5000; // 50%

    /// @notice Close factor applied once a position's health factor drops below
    ///         {FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD} — allows a single {liquidate} call to
    ///         close the full outstanding debt instead of being capped at {CLOSE_FACTOR_BPS},
    ///         mirroring Aave v3's own close-factor escalation for severely unhealthy positions.
    uint256 public constant MAX_CLOSE_FACTOR_BPS = 10_000; // 100%

    /// @notice Health-factor threshold (WAD-scaled) below which {MAX_CLOSE_FACTOR_BPS} applies
    ///         instead of {CLOSE_FACTOR_BPS} — matches Aave v3's own CLOSE_FACTOR_HF_THRESHOLD.
    uint256 public constant FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD = 0.95e18;

    /// @notice Ceiling on the operator-settable {reserveFactorBps}, so depositors always keep
    ///         the majority of borrower interest.
    uint256 public constant MAX_RESERVE_FACTOR_BPS = 2500; // 25%

    /// @notice Ceiling on {liquidationBonusBps} at construction — without this, a market could
    ///         be created with an unbounded liquidator windfall.
    uint256 public constant MAX_LIQUIDATION_BONUS_BPS = 2000; // 20%

    /// @notice During the stale-price grace window (see {_currentPriceForLiquidation}), a
    ///         position must be unhealthy by this much more than the normal 1.0 threshold before
    ///         {liquidate} will act on it — a safety margin for the borrower against a mark that
    ///         may no longer reflect the true price, in either direction.
    uint256 public constant STALE_GRACE_HEALTH_FACTOR_BUFFER_BPS = 500; // 5%

    // ── Immutable market identity ────────────────────────────────────────────

    /// @notice The stablecoin lenders supply and borrowers draw against pledged collateral.
    IERC20 public immutable loanToken;
    /// @notice The single restricted security-token collateral this market accepts.
    IERC20 public immutable collateralToken;
    /// @notice Price feed for {collateralToken}, denominated in {loanToken} base units.
    IRepoOracle public immutable priceOracle;
    /// @notice Maximum LTV a new {pledgeAndBorrow} may open a position at — strictly below
    ///         {lltvBps}, so a borrower who draws the maximum this market allows is not
    ///         immediately liquidatable the moment any interest accrues. Separate
    ///         from {lltvBps} unlike this market's earlier single-threshold design (which mirrored
    ///         Morpho Blue's "LLTV serves both purposes" convention) — restores the origination
    ///         buffer `EwpgRepoFacility` always enforced.
    uint256 public immutable maxLtvBps;
    /// @notice Liquidation LTV, in bps — the health-factor threshold. Always > {maxLtvBps}.
    uint256 public immutable lltvBps;
    /// @notice Extra collateral (in bps of debt repaid) awarded to a liquidator. Capped at
    ///         {MAX_LIQUIDATION_BONUS_BPS}.
    uint256 public immutable liquidationBonusBps;
    /// @notice Annualized rate-curve constants, WAD-scaled: borrowRate = baseRateWad +
    ///         slopeWad * utilization.
    uint256 public immutable baseRateWad;
    uint256 public immutable slopeWad;
    /// @notice Maximum age of a price mark before it is rejected as stale, in seconds.
    ///         `0` disables the staleness check (test/demo markets only).
    uint256 public immutable maxPriceAgeSeconds;
    /// @notice Wider staleness tolerance for {liquidate} only — must be
    ///         >= {maxPriceAgeSeconds} when staleness is enabled. See
    ///         {_currentPriceForLiquidation} for the full rationale.
    uint256 public immutable liquidationGracePeriodSeconds;

    // ── Mutable operator economics ───────────────────────────────────────────

    /// @notice Fraction of borrower interest retained by the protocol instead of flowing to
    ///         depositors, in bps. Operator-settable, capped at {MAX_RESERVE_FACTOR_BPS}.
    uint256 public reserveFactorBps;
    /// @notice Underlying-token reserves accumulated for the protocol, withdrawable by the
    ///         operator via {withdrawReserves}.
    uint256 public totalReserves;
    /// @notice When true, new borrowing is blocked; {repay}/{liquidate} remain available on
    ///         principle — reducing risk should never be blocked by an emergency pause.
    bool public borrowPaused;

    struct Position {
        uint256 collateralAmount;
        uint256 scaledDebt; // actual debt = scaledDebt * borrowIndex / WAD
    }

    /// @notice borrower => position. One position per borrower — this market has only one
    ///         collateral asset, unlike `EwpgRepoFacility`'s per-collateral-token mapping.
    mapping(address => Position) public positions;

    uint256 public liquidityIndex = WAD;
    uint256 public borrowIndex = WAD;
    uint256 public totalScaledDeposits;
    uint256 public totalScaledDebt;
    uint256 public lastAccrualTimestamp;

    mapping(address => uint256) public scaledDepositOf;

    event Supplied(address indexed lender, uint256 amount, uint256 scaledAmount);
    event Withdrawn(address indexed lender, uint256 amount, uint256 scaledAmount);
    event Borrowed(address indexed borrower, uint256 collateralAmount, uint256 borrowAmount);
    event CollateralAdded(address indexed borrower, uint256 amount, uint256 totalCollateral);
    event CollateralWithdrawn(address indexed borrower, uint256 amount, uint256 remainingCollateral);
    event Repaid(address indexed borrower, uint256 repayAmount, uint256 collateralReturned);
    event Liquidated(
        address indexed borrower, address indexed liquidator, uint256 debtRepaid, uint256 collateralSeized
    );
    event ReserveFactorUpdated(uint256 reserveFactorBps);
    event ReservesWithdrawn(address indexed to, uint256 amount);
    event BorrowPausedSet(bool paused);
    event CollateralReconciled(address indexed borrower, uint256 previousCollateral, uint256 newCollateral);
    /// @notice A borrower's collateral was fully exhausted (via {liquidate} or
    ///         {reconcileCollateral}) while debt remained outstanding — that debt is now
    ///         written off rather than left to compound phantom interest forever.
    ///         `lossToDepositors` is the underlying-token amount by which every
    ///         depositor's claim was proportionally reduced to absorb it.
    event BadDebtRecognized(address indexed borrower, uint256 writtenOffDebt, uint256 lossToDepositors);

    error ZeroAddress();
    error ZeroAmount();
    error InvalidLltv();
    error InvalidMaxLtv();
    error InvalidLiquidationBonus();
    error InvalidCollateralDecimals();
    error InsufficientLiquidationHaircut();
    error InvalidLiquidationGracePeriod();
    error InvalidReserveFactor();
    error BorrowIsPaused();
    error StalePrice(uint256 updatedAt, uint256 currentTimestamp);
    error PriceNotSet();
    error InsufficientPoolLiquidity();
    error InsufficientCollateral();
    error ExceedsLltv();
    error PositionHealthy(address borrower);
    error NoOutstandingDebt();
    error InsufficientShares();
    error InsufficientReserves();
    error ReconciliationWouldIncreaseCollateral();

    constructor(
        IPermissionOracle oracle_,
        IERC20 loanToken_,
        IERC20 collateralToken_,
        IRepoOracle priceOracle_,
        uint256 maxLtvBps_,
        uint256 lltvBps_,
        uint256 liquidationBonusBps_,
        uint256 baseRateWad_,
        uint256 slopeWad_,
        uint256 maxPriceAgeSeconds_,
        uint256 liquidationGracePeriodSeconds_
    ) RegisterwerkGated(oracle_) {
        if (
            address(loanToken_) == address(0) || address(collateralToken_) == address(0)
                || address(priceOracle_) == address(0)
        ) revert ZeroAddress();
        if (lltvBps_ == 0 || lltvBps_ > BPS_DENOMINATOR) revert InvalidLltv();
        if (maxLtvBps_ == 0 || maxLtvBps_ >= lltvBps_) revert InvalidMaxLtv();
        if (liquidationBonusBps_ > MAX_LIQUIDATION_BONUS_BPS) revert InvalidLiquidationBonus();
        if (IERC20Metadata(address(collateralToken_)).decimals() != 0) revert InvalidCollateralDecimals();
        // A haircut no wider than the oracle's own routine per-push deviation tolerance means a
        // single ordinary, in-tolerance price push could already leave a liquidation
        // under-collateralized — checked as a construction-time snapshot, same limitation
        // {maxPriceAgeSeconds} already accepts (an immutable risk parameter that doesn't track
        // a later change to what the "right" value would be).
        if (BPS_DENOMINATOR - lltvBps_ < priceOracle_.maxDeviationBps()) revert InsufficientLiquidationHaircut();
        if (maxPriceAgeSeconds_ != 0 && liquidationGracePeriodSeconds_ < maxPriceAgeSeconds_) {
            revert InvalidLiquidationGracePeriod();
        }

        loanToken = loanToken_;
        collateralToken = collateralToken_;
        priceOracle = priceOracle_;
        maxLtvBps = maxLtvBps_;
        lltvBps = lltvBps_;
        liquidationBonusBps = liquidationBonusBps_;
        baseRateWad = baseRateWad_;
        slopeWad = slopeWad_;
        maxPriceAgeSeconds = maxPriceAgeSeconds_;
        liquidationGracePeriodSeconds = liquidationGracePeriodSeconds_;
        lastAccrualTimestamp = block.timestamp;
    }

    // ── Lender side — open to any stablecoin holder ─────────────────────────

    /// @notice Supplies `amount` of {loanToken} to the pool, minted as index-scaled shares.
    function supply(uint256 amount) external nonReentrant returns (uint256 scaledAmount) {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        // Lenders receive no more claim than the assets supplied. At an index above WAD a
        // sufficiently small amount can floor to zero; reject it before moving any cash.
        scaledAmount = Math.mulDiv(amount, WAD, liquidityIndex);
        if (scaledAmount == 0) revert ZeroAmount();
        scaledDepositOf[msg.sender] += scaledAmount;
        totalScaledDeposits += scaledAmount;
        loanToken.safeTransferFrom(msg.sender, address(this), amount);
        emit Supplied(msg.sender, amount, scaledAmount);
    }

    /// @notice Withdraws up to `amount` of {loanToken}, limited by the caller's current claim
    ///         and by the pool's available (unborrowed) cash.
    function withdraw(uint256 amount) external nonReentrant returns (uint256 scaledAmount) {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        // Round the shares burned up so every non-zero asset withdrawal consumes
        // a non-zero claim and can never transfer more than the burned shares are
        // worth. Floor rounding allowed dust withdrawals to burn zero shares once
        // the liquidity index had grown above WAD.
        scaledAmount = Math.mulDiv(amount, WAD, liquidityIndex, Math.Rounding.Ceil);
        if (scaledAmount > scaledDepositOf[msg.sender]) revert InsufficientShares();
        if (amount > loanToken.balanceOf(address(this))) revert InsufficientPoolLiquidity();
        scaledDepositOf[msg.sender] -= scaledAmount;
        totalScaledDeposits -= scaledAmount;
        loanToken.safeTransfer(msg.sender, amount);
        emit Withdrawn(msg.sender, amount, scaledAmount);
    }

    /// @notice Current claim of `lender` in {loanToken} base units.
    function balanceOf(address lender) external view returns (uint256) {
        (uint256 projectedLiquidityIndex,,) = _pendingIndices();
        return Math.mulDiv(scaledDepositOf[lender], projectedLiquidityIndex, WAD);
    }

    // ── Operator configuration ───────────────────────────────────────────────

    /// @notice Sets the protocol's share of borrower interest, capped at
    ///         {MAX_RESERVE_FACTOR_BPS}.
    function setReserveFactor(uint256 newReserveFactorBps) external requiresPermission(CONFIGURE) {
        if (newReserveFactorBps > MAX_RESERVE_FACTOR_BPS) revert InvalidReserveFactor();
        _accrue();
        reserveFactorBps = newReserveFactorBps;
        emit ReserveFactorUpdated(newReserveFactorBps);
    }

    /// @notice Withdraws up to `amount` of accumulated protocol reserves to `to`.
    function withdrawReserves(address to, uint256 amount) external requiresPermission(CONFIGURE) {
        if (to == address(0)) revert ZeroAddress();
        _accrue();
        if (amount > totalReserves) revert InsufficientReserves();
        if (amount > loanToken.balanceOf(address(this))) revert InsufficientPoolLiquidity();
        totalReserves -= amount;
        loanToken.safeTransfer(to, amount);
        emit ReservesWithdrawn(to, amount);
    }

    /// @notice Emergency-pauses (or resumes) new borrowing. {repay}/{liquidate} are never
    ///         affected — see the contract-level NatSpec.
    function setBorrowPaused(bool paused) external requiresPermission(CONFIGURE) {
        borrowPaused = paused;
        emit BorrowPausedSet(paused);
    }

    /// @notice Reconciles `borrower`'s recorded pledged-collateral amount down to
    ///         `attributableCollateral`, after an issuer/agent `forcedTransfer` or `forceBurn`
    ///         on {collateralToken} moved tokens out of this market's balance outside the normal
    ///         {repay}/{liquidate} paths (eWpG §24 Berichtigung; an AWG/GwG freeze or a court
    ///         order can trigger such a forced move at the token layer, which this market's
    ///         internal `positions` accounting has no way to observe on its own). Left
    ///         unreconciled, the position's recorded collateral would exceed what the market can
    ///         actually deliver, causing {repay}/{liquidate} to revert or over-pay out of other
    ///         borrowers'/lenders' funds.
    ///
    /// @dev Takes the corrected amount as an explicit parameter rather than trying to infer it
    ///      from `collateralToken.balanceOf(address(this))`: that balance is the sum across
    ///      every borrower in this market, so only an off-chain reconciliation of the specific
    ///      forced-transfer transaction (the same operator act that ordered the forced transfer
    ///      in the first place) can correctly attribute the reduction to this one borrower. The
    ///      only on-chain invariant enforced here is that reconciliation can never increase a
    ///      position's collateral — it can only correct it down to what was actually seen
    ///      leaving the pool, never fabricate collateral that was never pledged.
    function reconcileCollateral(address borrower, uint256 attributableCollateral)
        external
        requiresPermission(CONFIGURE)
    {
        _accrue();
        Position storage pos = positions[borrower];
        if (attributableCollateral >= pos.collateralAmount) revert ReconciliationWouldIncreaseCollateral();
        uint256 previous = pos.collateralAmount;
        pos.collateralAmount = attributableCollateral;
        emit CollateralReconciled(borrower, previous, attributableCollateral);

        if (attributableCollateral == 0 && pos.scaledDebt > 0) {
            _writeOffBadDebt(borrower, pos);
        }
    }

    // ── Borrower side — gated: only verified investors may pledge & borrow ──

    /// @notice Pledges `collateralAmount` of {collateralToken} and borrows up to `maxLtvBps` of
    ///         the combined (existing + new) position's value. Reverts at the T-REX layer if
    ///         this market is not a verified, nominee-flagged holder of {collateralToken}.
    function pledgeAndBorrow(uint256 collateralAmount, uint256 borrowAmount)
        external
        nonReentrant
        requiresPermission(BORROW)
        requiresClaim(TOPIC_KYC)
    {
        if (borrowPaused) revert BorrowIsPaused();
        if (collateralAmount == 0 || borrowAmount == 0) revert ZeroAmount();
        _accrue();

        uint256 pricePerUnit = _currentPrice();
        Position storage pos = positions[msg.sender];
        uint256 newCollateral = pos.collateralAmount + collateralAmount;
        // Debt shares round up so the position can never receive more cash than it records as
        // debt. Check LTV against that recorded post-mint debt, not the requested cash amount.
        uint256 addedScaledDebt = Math.mulDiv(borrowAmount, WAD, borrowIndex, Math.Rounding.Ceil);
        uint256 newScaledDebt = pos.scaledDebt + addedScaledDebt;
        uint256 recordedNewDebt = Math.mulDiv(newScaledDebt, borrowIndex, WAD);
        uint256 collateralValue = Math.mulDiv(newCollateral, pricePerUnit, 1);
        uint256 maxDebt = Math.mulDiv(collateralValue, maxLtvBps, BPS_DENOMINATOR);
        if (recordedNewDebt > maxDebt) revert ExceedsLltv();
        if (borrowAmount > loanToken.balanceOf(address(this))) revert InsufficientPoolLiquidity();

        collateralToken.safeTransferFrom(msg.sender, address(this), collateralAmount);

        pos.collateralAmount = newCollateral;
        pos.scaledDebt = newScaledDebt;
        totalScaledDebt += addedScaledDebt;

        loanToken.safeTransfer(msg.sender, borrowAmount);
        emit Borrowed(msg.sender, collateralAmount, borrowAmount);
    }

    /// @notice Adds collateral to an existing loan without drawing more cash. This risk-reducing
    ///         path deliberately remains available if an ecosystem permission is later revoked;
    ///         the restricted collateral token still enforces its own transfer eligibility.
    function addCollateral(uint256 amount) external nonReentrant {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        Position storage pos = positions[msg.sender];
        if (pos.scaledDebt == 0) revert NoOutstandingDebt();

        collateralToken.safeTransferFrom(msg.sender, address(this), amount);
        pos.collateralAmount += amount;
        emit CollateralAdded(msg.sender, amount, pos.collateralAmount);
    }

    /// @notice Withdraws excess collateral while keeping the remaining position at or below the
    ///         market's origination LTV. Unlike repayment/add-collateral this increases pool risk,
    ///         so it requires the normal borrower permission, KYC claim, and a current price.
    function withdrawCollateral(uint256 amount)
        external
        nonReentrant
        requiresPermission(BORROW)
        requiresClaim(TOPIC_KYC)
    {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        Position storage pos = positions[msg.sender];
        if (pos.scaledDebt == 0) revert NoOutstandingDebt();
        if (amount > pos.collateralAmount) revert InsufficientCollateral();

        uint256 remainingCollateral = pos.collateralAmount - amount;
        uint256 currentDebt = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        uint256 collateralValue = Math.mulDiv(remainingCollateral, _currentPrice(), 1);
        uint256 maxDebt = Math.mulDiv(collateralValue, maxLtvBps, BPS_DENOMINATOR);
        if (currentDebt > maxDebt) revert ExceedsLltv();

        pos.collateralAmount = remainingCollateral;
        collateralToken.safeTransfer(msg.sender, amount);
        emit CollateralWithdrawn(msg.sender, amount, remainingCollateral);
    }

    /// @notice Repays up to `repayAmount` (capped to the current outstanding debt) and
    ///         releases a proportional share of the pledged collateral. Not gated by
    ///         {RegisterwerkGated} — see the contract-level NatSpec for why.
    function repay(uint256 repayAmount) external nonReentrant returns (uint256 collateralReturned) {
        _accrue();
        Position storage pos = positions[msg.sender];
        uint256 currentDebt = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        if (currentDebt == 0) revert NoOutstandingDebt();

        uint256 collateralBefore = pos.collateralAmount;
        (uint256 residualScaledDebt, uint256 actualRepayAmount) =
            _residualDebtAfterPayment(pos.scaledDebt, currentDebt, repayAmount);
        uint256 scaledRepaid = pos.scaledDebt - residualScaledDebt;

        if (residualScaledDebt == 0) {
            // Full exit is explicit: no debt shares and no inaccessible collateral dust.
            collateralReturned = collateralBefore;
        } else {
            collateralReturned = Math.mulDiv(collateralBefore, actualRepayAmount, currentDebt);
        }
        pos.scaledDebt = residualScaledDebt;
        totalScaledDebt -= scaledRepaid;
        pos.collateralAmount = collateralBefore - collateralReturned;

        loanToken.safeTransferFrom(msg.sender, address(this), actualRepayAmount);
        collateralToken.safeTransfer(msg.sender, collateralReturned);
        emit Repaid(msg.sender, actualRepayAmount, collateralReturned);
    }

    /// @notice Liquidation of an under-collateralized position (health factor below 1.0, or
    ///         below the stale-grace-period threshold — see {_currentPriceForLiquidation}): the
    ///         caller repays up to {CLOSE_FACTOR_BPS} of the position's outstanding debt (capped
    ///         to `maxRepayAmount` if it requests less), or up to {MAX_CLOSE_FACTOR_BPS} (the
    ///         full debt) once the position is severely underwater — see
    ///         {FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD} — and receives the corresponding
    ///         collateral plus the configured liquidation bonus. May be called repeatedly while
    ///         the position remains unhealthy. Not gated by {RegisterwerkGated} — see the
    ///         contract-level NatSpec for why an unverified caller cannot actually succeed.
    function liquidate(address borrower, uint256 maxRepayAmount)
        external
        nonReentrant
        returns (uint256 debtRepaid, uint256 collateralSeized)
    {
        _accrue();
        Position storage pos = positions[borrower];
        uint256 currentDebt = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        if (currentDebt == 0) revert NoOutstandingDebt();

        (uint256 pricePerUnit, uint256 requestedRepay) =
            _liquidationPaymentLimit(pos, borrower, currentDebt, maxRepayAmount);
        uint256 residualCollateral;
        (debtRepaid, collateralSeized, residualCollateral) =
            _applyLiquidationPayment(pos, currentDebt, requestedRepay, pricePerUnit);

        loanToken.safeTransferFrom(msg.sender, address(this), debtRepaid);
        collateralToken.safeTransfer(msg.sender, collateralSeized);
        if (residualCollateral > 0) {
            collateralToken.safeTransfer(borrower, residualCollateral);
        }
        emit Liquidated(borrower, msg.sender, debtRepaid, collateralSeized);

        if (pos.collateralAmount == 0 && pos.scaledDebt > 0) {
            _writeOffBadDebt(borrower, pos);
        }
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /// @notice Current outstanding debt of `borrower`.
    function debtOf(address borrower) external view returns (uint256) {
        (, uint256 projectedBorrowIndex,) = _pendingIndices();
        return Math.mulDiv(positions[borrower].scaledDebt, projectedBorrowIndex, WAD);
    }

    /// @notice Health factor, WAD-scaled (>= 1e18 is healthy, < 1e18 is liquidatable).
    ///         `type(uint256).max` when there is no outstanding debt. Never reverts — a polled
    ///         view function reverting on every unpriced/stale position is worse for callers
    ///         than an honest, self-describing answer. `priceReliable` is
    ///         false when the collateral has never been priced OR the mark is older than
    ///         {maxPriceAgeSeconds}: callers must not treat `factor` as trustworthy in that case
    ///         (previously the NatSpec claimed this reverts when unpriced; the implementation
    ///         actually returned a bare `0` — the "liquidate now" value — which is a misleading
    ///         signal for a merely-unpriced position, not a real health assessment).
    function healthFactor(address borrower) external view returns (uint256 factor, bool priceReliable) {
        (, uint256 projectedBorrowIndex,) = _pendingIndices();
        Position storage pos = positions[borrower];
        uint256 debt = Math.mulDiv(pos.scaledDebt, projectedBorrowIndex, WAD);
        (uint256 pricePerUnit, uint256 updatedAt) = priceOracle.price(address(collateralToken));
        priceReliable = pricePerUnit != 0 && updatedAt <= block.timestamp
            && (maxPriceAgeSeconds == 0 || block.timestamp - updatedAt <= maxPriceAgeSeconds);
        factor = _healthFactorFor(pos.collateralAmount, pricePerUnit, debt);
    }

    /// @notice Pool utilization, WAD-scaled: outstanding debt / (outstanding debt + cash).
    function utilization() public view returns (uint256) {
        uint256 debt = Math.mulDiv(totalScaledDebt, borrowIndex, WAD);
        uint256 cash = loanToken.balanceOf(address(this));
        uint256 total = debt + cash;
        if (total == 0) return 0;
        return Math.mulDiv(debt, WAD, total);
    }

    /// @notice Current annualized borrow rate, WAD-scaled.
    function borrowRate() external view returns (uint256) {
        return baseRateWad + Math.mulDiv(slopeWad, utilization(), WAD);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /// @dev Applies the existing health-factor and close-factor policy and returns the maximum
    ///      requested asset payment this liquidation call may apply. Kept separate from the
    ///      accounting mutation so rounding locals cannot exhaust the EVM stack in {liquidate}.
    function _liquidationPaymentLimit(
        Position storage pos,
        address borrower,
        uint256 currentDebt,
        uint256 maxRepayAmount
    ) private view returns (uint256 pricePerUnit, uint256 requestedRepay) {
        bool withinGracePeriod;
        (pricePerUnit, withinGracePeriod) = _currentPriceForLiquidation();
        uint256 factor = _healthFactorFor(pos.collateralAmount, pricePerUnit, currentDebt);
        uint256 threshold = withinGracePeriod
            ? Math.mulDiv(WAD, BPS_DENOMINATOR - STALE_GRACE_HEALTH_FACTOR_BUFFER_BPS, BPS_DENOMINATOR)
            : WAD;
        if (factor >= threshold) revert PositionHealthy(borrower);

        // Severity-scaled close factor: preserve the existing 50%/100% policy exactly.
        uint256 effectiveCloseFactorBps =
            factor < FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD ? MAX_CLOSE_FACTOR_BPS : CLOSE_FACTOR_BPS;
        uint256 maxCloseable = Math.mulDiv(currentDebt, effectiveCloseFactorBps, BPS_DENOMINATOR);
        // Dust fallback: a close-factor result of zero may close the full remaining debt.
        if (maxCloseable == 0) {
            maxCloseable = currentDebt;
        }
        requestedRepay = maxRepayAmount < maxCloseable ? maxRepayAmount : maxCloseable;
        if (requestedRepay == 0) revert ZeroAmount();
    }

    /// @dev Applies conservative debt-share rounding and collateral accounting for liquidation.
    ///      Returns residual collateral separately so the external function can transfer it only
    ///      after all state effects are committed.
    function _applyLiquidationPayment(
        Position storage pos,
        uint256 currentDebt,
        uint256 requestedRepay,
        uint256 pricePerUnit
    ) private returns (uint256 debtRepaid, uint256 collateralSeized, uint256 residualCollateral) {
        uint256 collateralBefore = pos.collateralAmount;
        (uint256 residualScaledDebt, uint256 actualRepayAmount) =
            _residualDebtAfterPayment(pos.scaledDebt, currentDebt, requestedRepay);
        debtRepaid = actualRepayAmount;

        uint256 seizeValue = Math.mulDiv(debtRepaid, BPS_DENOMINATOR + liquidationBonusBps, BPS_DENOMINATOR);
        collateralSeized = seizeValue / pricePerUnit;
        if (collateralSeized > collateralBefore) {
            collateralSeized = collateralBefore;
        }

        uint256 scaledRepaid = pos.scaledDebt - residualScaledDebt;
        pos.scaledDebt = residualScaledDebt;
        totalScaledDebt -= scaledRepaid;
        pos.collateralAmount = collateralBefore - collateralSeized;

        if (residualScaledDebt == 0 && pos.collateralAmount > 0) {
            // Once debt is fully closed, any collateral beyond the liquidator's entitlement must
            // leave custody as well; otherwise the borrower has no remaining function to claim it.
            residualCollateral = pos.collateralAmount;
            pos.collateralAmount = 0;
        }
    }

    /// @dev Computes a conservative residual scaled debt for a requested asset payment. Partial
    ///      payments ceiling-round the residual shares so debt never falls by more cash than the
    ///      payer supplies; the returned amount is the actual before/after debt delta. A request
    ///      covering the full displayed debt is an explicit full exit and burns every debt share.
    function _residualDebtAfterPayment(uint256 scaledDebtBefore, uint256 debtBefore, uint256 requestedPayment)
        private
        view
        returns (uint256 residualScaledDebt, uint256 actualPayment)
    {
        if (requestedPayment >= debtBefore) {
            return (0, debtBefore);
        }
        if (requestedPayment == 0) revert ZeroAmount();

        uint256 targetResidualDebt = debtBefore - requestedPayment;
        residualScaledDebt = Math.mulDiv(targetResidualDebt, WAD, borrowIndex, Math.Rounding.Ceil);
        if (residualScaledDebt >= scaledDebtBefore) revert ZeroAmount();

        uint256 residualDebt = Math.mulDiv(residualScaledDebt, borrowIndex, WAD);
        actualPayment = debtBefore - residualDebt;
        if (actualPayment == 0) revert ZeroAmount();
    }

    function _currentPrice() private view returns (uint256 pricePerUnit) {
        uint256 updatedAt;
        (pricePerUnit, updatedAt) = priceOracle.price(address(collateralToken));
        if (pricePerUnit == 0) revert PriceNotSet();
        if (
            maxPriceAgeSeconds != 0
                && (updatedAt > block.timestamp || block.timestamp - updatedAt > maxPriceAgeSeconds)
        ) {
            revert StalePrice(updatedAt, block.timestamp);
        }
    }

    /// @dev Wider staleness tolerance for {liquidate} only (per a joint Repo/Lending-desk and
    ///      trading-desk business ruling) — risk-reduction should not
    ///      share {pledgeAndBorrow}'s freshness bar: a liquidator's only recourse if this
    ///      reverted would be waiting on an operator to push a fresh mark or invoke
    ///      {RegisterwerkNavOracle-pushPriceWithOverride}, while the pool's exposure to an
    ///      under-collateralized position keeps growing — and that operator may be unavailable
    ///      during exactly the incident that made the feed go stale in the first place. A price
    ///      this stale is still bounded in time (never older than
    ///      {liquidationGracePeriodSeconds}), and {liquidate} additionally demands the position
    ///      be unhealthy by {STALE_GRACE_HEALTH_FACTOR_BUFFER_BPS} more than usual whenever
    ///      `withinGracePeriod` is true — a safety margin protecting the borrower against a mark
    ///      that may no longer reflect the true price, in either direction, while still letting
    ///      lenders de-risk a position that is unambiguously bad even under that stricter bar.
    ///      Beyond {liquidationGracePeriodSeconds}, a mark is not a legitimate valuation under
    ///      any theory and this still reverts exactly like {_currentPrice} —
    ///      {RegisterwerkNavOracle.pushPriceWithOverride} is the designated remediation path.
    function _currentPriceForLiquidation() private view returns (uint256 pricePerUnit, bool withinGracePeriod) {
        uint256 updatedAt;
        (pricePerUnit, updatedAt) = priceOracle.price(address(collateralToken));
        if (pricePerUnit == 0) revert PriceNotSet();
        if (maxPriceAgeSeconds == 0) {
            return (pricePerUnit, false);
        }
        if (updatedAt > block.timestamp) revert StalePrice(updatedAt, block.timestamp);
        uint256 age = block.timestamp - updatedAt;
        if (age <= maxPriceAgeSeconds) {
            return (pricePerUnit, false);
        }
        if (age > liquidationGracePeriodSeconds) {
            revert StalePrice(updatedAt, block.timestamp);
        }
        return (pricePerUnit, true);
    }

    function _healthFactorFor(uint256 collateralAmount, uint256 pricePerUnit, uint256 debt)
        private
        view
        returns (uint256)
    {
        if (debt == 0) return type(uint256).max;
        if (pricePerUnit == 0) return 0; // unpriced collateral cannot back any debt safely
        uint256 collateralValue = Math.mulDiv(collateralAmount, pricePerUnit, 1);
        uint256 adjustedValue = Math.mulDiv(collateralValue, lltvBps, BPS_DENOMINATOR);
        return Math.mulDiv(adjustedValue, WAD, debt);
    }

    /// @dev Writes off a borrower's entire remaining debt once their collateral is fully
    ///      exhausted — called from {liquidate} and {reconcileCollateral},
    ///      the only two paths that can zero `pos.collateralAmount` while `pos.scaledDebt`
    ///      remains. Without this, {_accrue} would keep compounding "interest" on debt that can
    ///      never actually be recovered, silently inflating every depositor's `balanceOf` claim
    ///      with value the pool doesn't have. The loss is instead recognized once, immediately,
    ///      and spread proportionally across all depositors by reducing {liquidityIndex} —
    ///      exactly inverting what {_accrue} does when interest is earned, so every depositor's
    ///      claim absorbs their pro-rata share of the loss the moment it's realized rather than
    ///      leaving it to be discovered later as an unexplained withdrawal shortfall.
    ///
    ///      This is a detection-and-immediate-recognition mechanism only — it does not attempt
    ///      reserve-first absorption, a first-loss tranche, or any other socialization policy
    ///      beyond "every depositor eats their share equally"; a more nuanced write-down design
    ///      is intentionally not automated by this contract.
    function _writeOffBadDebt(address borrower, Position storage pos) private {
        uint256 writtenOff = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        totalScaledDebt -= pos.scaledDebt;
        pos.scaledDebt = 0;

        uint256 lossToDepositors = 0;
        if (writtenOff > 0 && totalScaledDeposits > 0) {
            uint256 totalDepositsUnderlying = Math.mulDiv(totalScaledDeposits, liquidityIndex, WAD);
            if (totalDepositsUnderlying > 0) {
                lossToDepositors = writtenOff > totalDepositsUnderlying ? totalDepositsUnderlying : writtenOff;
                liquidityIndex -= Math.mulDiv(liquidityIndex, lossToDepositors, totalDepositsUnderlying);
            }
        }
        emit BadDebtRecognized(borrower, writtenOff, lossToDepositors);
    }

    /// @dev Accrues interest for the elapsed period, splitting it exactly between the protocol
    ///      reserve ({reserveFactorBps} share) and depositors (the remainder), then rolls
    ///      `lastAccrualTimestamp` forward. Unlike `EwpgRepoFacility`'s rate-based
    ///      approximation, this computes the actual underlying-token interest amount so the
    ///      reserve split is exact regardless of utilization drift within the period.
    function _accrue() private {
        uint256 timeDelta = block.timestamp - lastAccrualTimestamp;
        if (timeDelta == 0) return;
        lastAccrualTimestamp = block.timestamp;
        if (totalScaledDebt == 0) return;

        uint256 debtBefore = Math.mulDiv(totalScaledDebt, borrowIndex, WAD);

        uint256 util = utilization();
        uint256 rate = baseRateWad + Math.mulDiv(slopeWad, util, WAD);
        uint256 growth = Math.mulDiv(rate, timeDelta, SECONDS_PER_YEAR);
        borrowIndex += Math.mulDiv(borrowIndex, growth, WAD);

        uint256 debtAfter = Math.mulDiv(totalScaledDebt, borrowIndex, WAD);
        uint256 interestAccrued = debtAfter - debtBefore;

        uint256 reserveShare = Math.mulDiv(interestAccrued, reserveFactorBps, BPS_DENOMINATOR);
        if (reserveShare > 0) {
            totalReserves += reserveShare;
        }

        uint256 depositorShare = interestAccrued - reserveShare;
        if (depositorShare > 0 && totalScaledDeposits > 0) {
            uint256 totalDepositsUnderlying = Math.mulDiv(totalScaledDeposits, liquidityIndex, WAD);
            if (totalDepositsUnderlying > 0) {
                liquidityIndex += Math.mulDiv(liquidityIndex, depositorShare, totalDepositsUnderlying);
            }
        }
    }

    /// @dev View-only projection of what {_accrue} would do, without mutating state. Returns
    ///      the projected reserve share too so {debtOf}/{balanceOf} and any future view can
    ///      stay consistent with a subsequent real accrual.
    function _pendingIndices()
        private
        view
        returns (uint256 projectedLiquidityIndex, uint256 projectedBorrowIndex, uint256 projectedReserveShare)
    {
        uint256 timeDelta = block.timestamp - lastAccrualTimestamp;
        if (timeDelta == 0 || totalScaledDebt == 0) {
            return (liquidityIndex, borrowIndex, 0);
        }

        uint256 debtBefore = Math.mulDiv(totalScaledDebt, borrowIndex, WAD);
        uint256 util = utilization();
        uint256 rate = baseRateWad + Math.mulDiv(slopeWad, util, WAD);
        uint256 growth = Math.mulDiv(rate, timeDelta, SECONDS_PER_YEAR);
        projectedBorrowIndex = borrowIndex + Math.mulDiv(borrowIndex, growth, WAD);

        uint256 debtAfter = Math.mulDiv(totalScaledDebt, projectedBorrowIndex, WAD);
        uint256 interestAccrued = debtAfter - debtBefore;
        projectedReserveShare = Math.mulDiv(interestAccrued, reserveFactorBps, BPS_DENOMINATOR);
        uint256 depositorShare = interestAccrued - projectedReserveShare;

        projectedLiquidityIndex = liquidityIndex;
        if (depositorShare > 0 && totalScaledDeposits > 0) {
            uint256 totalDepositsUnderlying = Math.mulDiv(totalScaledDeposits, liquidityIndex, WAD);
            if (totalDepositsUnderlying > 0) {
                projectedLiquidityIndex += Math.mulDiv(liquidityIndex, depositorShare, totalDepositsUnderlying);
            }
        }
    }
}
