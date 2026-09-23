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

/// @notice Construction parameters of an {EwpgRepoMarket} — each maps 1:1 to the market's
///         immutable of the same name. All fields are static, so the ABI encoding equals the
///         flat parameter list.
struct MarketParams {
    address operatorOrg;
    address treasury;
    IERC20 loanToken;
    IERC20 collateralToken;
    IRepoOracle priceOracle;
    uint256 maxLtvBps;
    uint256 lltvBps;
    uint256 liquidationBonusBps;
    uint256 baseRateWad;
    uint256 slopeWad;
    uint256 maxPriceAgeSeconds;
    uint256 liquidationGracePeriodSeconds;
}

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
///         `repo-facility.borrow` plus a KYC claim, and {repay}/{repayDebtOnly}/
///         {claimCollateral}/{claimLiquidationSurplus}/{liquidate} are intentionally ungated at
///         this layer — the collateral token's own T-REX identity-registry check is the real,
///         sufficient compliance gate on any transfer out.
///
///         Operator functions are bound to this instance's {operatorOrg} (the org whose member
///         created the market through `EwpgRepoMarketFactory`) and use this dApp's own
///         `repo-markets.configure` / `repo-markets.reconcile` codes — never the facility's
///         `repo-facility.configure`, whose holders routinely push facility prices. Reserves
///         only ever go to the immutable {treasury}.
///
///         Debt repayment and collateral release are separable: {repay} does both in one call,
///         but a borrower whose collateral-token eligibility has lapsed (KYC expiry, country
///         change, wallet freeze, token pause) cannot receive collateral, so {repay} reverts for
///         them. {repayDebtOnly} lets that borrower still de-risk — it reduces the debt and
///         leaves the collateral pledged — and {claimCollateral} releases the collateral of a
///         zero-debt position once the token lets it move again. For the same reason a
///         full-close {liquidate} credits any residual collateral to the position instead of
///         pushing it to the borrower, so a frozen borrower cannot block the liquidation.
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
    /// @notice Reserve factor, reserve sweep to {treasury}, borrow pause.
    bytes32 public constant CONFIGURE = keccak256("repo-markets.configure");
    /// @notice Collateral write-down after an observed forced transfer — see {reconcileCollateral}.
    bytes32 public constant RECONCILE = keccak256("repo-markets.reconcile");
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
    ///         may no longer reflect the true price, in either direction. Within that window a
    ///         single call is also held to {CLOSE_FACTOR_BPS}, never the full close.
    uint256 public constant STALE_GRACE_HEALTH_FACTOR_BUFFER_BPS = 500; // 5%

    // ── Immutable market identity ────────────────────────────────────────────

    /// @notice Org operating this instance: only its members may call the operator functions.
    address public immutable operatorOrg;
    /// @notice The only recipient of {withdrawReserves}.
    address public immutable treasury;
    /// @notice The stablecoin lenders supply and borrowers draw against pledged collateral.
    IERC20 public immutable loanToken;
    /// @notice The single restricted security-token collateral this market accepts.
    IERC20 public immutable collateralToken;
    /// @notice Price feed for {collateralToken}, denominated in {loanToken} base units — its
    ///         `quoteToken()` must be {loanToken} (checked at construction).
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
    /// @notice Discount (in bps of debt repaid) at which a liquidator buys collateral. Capped
    ///         at {MAX_LIQUIDATION_BONUS_BPS}, and `lltvBps × (1 + bonus)` must stay below
    ///         `1 − priceOracle.maxDeviationBps()` (see the constructor), so a liquidation of a
    ///         position just below health factor 1 always leaves it healthier, never short.
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
    /// @notice When true, new borrowing is blocked; {repay}/{repayDebtOnly}/{claimCollateral}/
    ///         {liquidate} remain available on principle — reducing risk should never be
    ///         blocked by an emergency pause.
    bool public borrowPaused;

    struct Position {
        uint256 collateralAmount;
        uint256 scaledDebt; // actual debt = scaledDebt * borrowIndex / WAD
    }

    /// @notice borrower => position. One position per borrower — this market has only one
    ///         collateral asset, unlike `EwpgRepoFacility`'s per-collateral-token mapping.
    mapping(address => Position) public positions;

    /// @notice Sum of every position's `collateralAmount` — what this market must hold.
    ///         `collateralToken.balanceOf(this) < totalCollateral` is the only evidence of a
    ///         forced transfer out, and it bounds {reconcileCollateral}.
    uint256 public totalCollateral;

    /// @notice Loan-token cash owed to a liquidated borrower: the part of a liquidator's
    ///         payment for whole collateral units that exceeded the debt it closed. Held out of
    ///         pool liquidity and paid out by {claimLiquidationSurplus}.
    mapping(address => uint256) public surplusOf;
    /// @notice Sum of {surplusOf} — loan-token cash in this contract that belongs to borrowers.
    uint256 public totalSurplus;

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
    /// @notice `forcedTransferRef` links the write-down to the triggering forced-transfer
    ///         transaction (the backend's reconciliation record).
    event CollateralReconciled(
        address indexed borrower, uint256 previousCollateral, uint256 newCollateral, bytes32 forcedTransferRef
    );
    /// @notice A liquidation paid more for whole collateral units than the debt it closed; the
    ///         excess is owed to the borrower (see {surplusOf}).
    event LiquidationSurplusCredited(address indexed borrower, uint256 amount);
    event SurplusClaimed(address indexed borrower, uint256 amount);
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
    /// @notice `lltvBps × (1 + liquidationBonusBps) ≥ 1`: liquidating a still over-collateralised
    ///         position would already create bad debt.
    error InvalidLiquidationIncentive();
    error OracleQuoteMismatch(address oracleQuoteToken, address loanToken);
    error NoObservedShortfall();
    error ReconciliationExceedsShortfall(uint256 reduction, uint256 shortfall);
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
    error OutstandingDebt();

    /// @param p Market parameters — see {MarketParams}. `EwpgRepoMarketFactory` only accepts
    ///        its caller's own org as `p.operatorOrg`.
    constructor(IPermissionOracle oracle_, MarketParams memory p) RegisterwerkGated(oracle_) {
        _requireOrg(p.operatorOrg);
        if (
            p.treasury == address(0) || address(p.loanToken) == address(0)
                || address(p.collateralToken) == address(0) || address(p.priceOracle) == address(0)
        ) revert ZeroAddress();
        if (p.lltvBps == 0 || p.lltvBps > BPS_DENOMINATOR) revert InvalidLltv();
        if (p.maxLtvBps == 0 || p.maxLtvBps >= p.lltvBps) revert InvalidMaxLtv();
        if (p.liquidationBonusBps > MAX_LIQUIDATION_BONUS_BPS) revert InvalidLiquidationBonus();
        if (IERC20Metadata(address(p.collateralToken)).decimals() != 0) revert InvalidCollateralDecimals();
        // One oracle serves one quote currency: a EUR mark read by a USDC market misprices
        // every position.
        address quote = p.priceOracle.quoteToken();
        if (quote != address(p.loanToken)) revert OracleQuoteMismatch(quote, address(p.loanToken));
        _checkLiquidationHaircut(p.lltvBps, p.liquidationBonusBps, p.priceOracle.maxDeviationBps());
        if (p.maxPriceAgeSeconds != 0 && p.liquidationGracePeriodSeconds < p.maxPriceAgeSeconds) {
            revert InvalidLiquidationGracePeriod();
        }

        operatorOrg = p.operatorOrg;
        treasury = p.treasury;
        loanToken = p.loanToken;
        collateralToken = p.collateralToken;
        priceOracle = p.priceOracle;
        maxLtvBps = p.maxLtvBps;
        lltvBps = p.lltvBps;
        liquidationBonusBps = p.liquidationBonusBps;
        baseRateWad = p.baseRateWad;
        slopeWad = p.slopeWad;
        maxPriceAgeSeconds = p.maxPriceAgeSeconds;
        liquidationGracePeriodSeconds = p.liquidationGracePeriodSeconds;
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
        if (amount > _availableCash()) revert InsufficientPoolLiquidity();
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
    function setReserveFactor(uint256 newReserveFactorBps)
        external
        requiresOrgPermission(operatorOrg, CONFIGURE)
    {
        if (newReserveFactorBps > MAX_RESERVE_FACTOR_BPS) revert InvalidReserveFactor();
        _accrue();
        reserveFactorBps = newReserveFactorBps;
        emit ReserveFactorUpdated(newReserveFactorBps);
    }

    /// @notice Withdraws up to `amount` of accumulated protocol reserves to {treasury} — the
    ///         only possible recipient, so the configure grant cannot redirect pool cash.
    function withdrawReserves(uint256 amount) external requiresOrgPermission(operatorOrg, CONFIGURE) {
        _accrue();
        if (amount > totalReserves) revert InsufficientReserves();
        if (amount > _availableCash()) revert InsufficientPoolLiquidity();
        totalReserves -= amount;
        loanToken.safeTransfer(treasury, amount);
        emit ReservesWithdrawn(treasury, amount);
    }

    /// @notice Emergency-pauses (or resumes) new borrowing. {repay}/{liquidate} are never
    ///         affected — see the contract-level NatSpec.
    function setBorrowPaused(bool paused) external requiresOrgPermission(operatorOrg, CONFIGURE) {
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
    ///      in the first place) can correctly attribute the reduction to this one borrower. What
    ///      is enforced on-chain: reconciliation never increases a position's collateral, and the
    ///      total written down can never exceed the observed outflow — the shortfall
    ///      `totalCollateral − collateralToken.balanceOf(this)`, consumed cumulatively because
    ///      each write-down also lowers {totalCollateral}. A write-down (and the bad-debt
    ///      write-off it may trigger) without collateral actually having left is impossible.
    /// @param forcedTransferRef Reference to the triggering forced-transfer transaction.
    function reconcileCollateral(address borrower, uint256 attributableCollateral, bytes32 forcedTransferRef)
        external
        requiresOrgPermission(operatorOrg, RECONCILE)
    {
        _accrue();
        Position storage pos = positions[borrower];
        uint256 previous = pos.collateralAmount;
        if (attributableCollateral >= previous) revert ReconciliationWouldIncreaseCollateral();
        uint256 held = collateralToken.balanceOf(address(this));
        if (held >= totalCollateral) revert NoObservedShortfall();
        uint256 shortfall = totalCollateral - held;
        uint256 reduction = previous - attributableCollateral;
        if (reduction > shortfall) revert ReconciliationExceedsShortfall(reduction, shortfall);
        totalCollateral -= reduction;
        pos.collateralAmount = attributableCollateral;
        emit CollateralReconciled(borrower, previous, attributableCollateral, forcedTransferRef);

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
        if (borrowAmount > _availableCash()) revert InsufficientPoolLiquidity();

        collateralToken.safeTransferFrom(msg.sender, address(this), collateralAmount);

        pos.collateralAmount = newCollateral;
        totalCollateral += collateralAmount;
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
        totalCollateral += amount;
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
        totalCollateral -= amount;
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
        totalCollateral -= collateralReturned;

        loanToken.safeTransferFrom(msg.sender, address(this), actualRepayAmount);
        collateralToken.safeTransfer(msg.sender, collateralReturned);
        emit Repaid(msg.sender, actualRepayAmount, collateralReturned);
    }

    /// @notice Repays up to `repayAmount` (capped to the current outstanding debt) and keeps all
    ///         collateral pledged. Needs no collateral-token transfer, so it works while the
    ///         borrower is frozen or no longer verified on {collateralToken}, or while that token
    ///         is paused — situations in which {repay} reverts and the position could otherwise
    ///         only be liquidated. Once the debt reaches zero, {claimCollateral} releases the
    ///         collateral. Not gated by {RegisterwerkGated}, like {repay}.
    /// @dev Emits {Repaid} with `collateralReturned == 0`, so existing event consumers keep
    ///      working without a new event type.
    function repayDebtOnly(uint256 repayAmount) external nonReentrant returns (uint256 actualRepayAmount) {
        _accrue();
        Position storage pos = positions[msg.sender];
        uint256 currentDebt = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        if (currentDebt == 0) revert NoOutstandingDebt();

        uint256 residualScaledDebt;
        (residualScaledDebt, actualRepayAmount) = _residualDebtAfterPayment(pos.scaledDebt, currentDebt, repayAmount);
        totalScaledDebt -= pos.scaledDebt - residualScaledDebt;
        pos.scaledDebt = residualScaledDebt;

        loanToken.safeTransferFrom(msg.sender, address(this), actualRepayAmount);
        emit Repaid(msg.sender, actualRepayAmount, 0);
    }

    /// @notice Releases all collateral still held for the caller's position. Allowed only when
    ///         the position has no outstanding debt — after {repayDebtOnly} closed it, or after a
    ///         full-close {liquidate} credited the residual. Not gated by {RegisterwerkGated}: a
    ///         zero-debt position carries no pool risk, and {collateralToken} still enforces the
    ///         recipient's eligibility on the transfer itself.
    function claimCollateral() external nonReentrant returns (uint256 amount) {
        Position storage pos = positions[msg.sender];
        if (pos.scaledDebt != 0) revert OutstandingDebt();
        amount = pos.collateralAmount;
        if (amount == 0) revert ZeroAmount();

        pos.collateralAmount = 0;
        totalCollateral -= amount;
        collateralToken.safeTransfer(msg.sender, amount);
        emit CollateralWithdrawn(msg.sender, amount, 0);
    }

    /// @notice Pays out the caller's {surplusOf}: loan-token cash a liquidation credited because
    ///         the liquidator's payment for whole collateral units exceeded the debt it closed
    ///         (close-out netting — the excess belongs to the borrower). Not gated by
    ///         {RegisterwerkGated}, like {claimCollateral}.
    function claimLiquidationSurplus() external nonReentrant returns (uint256 amount) {
        amount = surplusOf[msg.sender];
        if (amount == 0) revert ZeroAmount();
        surplusOf[msg.sender] = 0;
        totalSurplus -= amount;
        loanToken.safeTransfer(msg.sender, amount);
        emit SurplusClaimed(msg.sender, amount);
    }

    /// @notice Liquidation of an under-collateralized position (health factor below 1.0, or
    ///         below the stale-grace-period threshold — see {_currentPriceForLiquidation}): the
    ///         caller closes up to {CLOSE_FACTOR_BPS} of the position's outstanding debt (capped
    ///         to `maxRepayAmount` if it requests less), or up to {MAX_CLOSE_FACTOR_BPS} (the
    ///         full debt) once the position is severely underwater with a fresh mark — see
    ///         {FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD}. May be called repeatedly while the
    ///         position remains unhealthy. Not gated by {RegisterwerkGated} — see the
    ///         contract-level NatSpec for why an unverified caller cannot actually succeed.
    ///
    ///         Collateral is whole units, so the liquidator buys whole units at the mark less
    ///         the bonus: `collateralSeized = ceil(requested × (1 + bonus) / price)` (at least
    ///         one, at most the pledged collateral), for a payment of `collateralSeized × price
    ///         / (1 + bonus)`. That payment reduces the debt; any part of it beyond the
    ///         remaining debt is credited to the borrower as {surplusOf}. Rounding up means the
    ///         payment — and the debt closed — can exceed the requested amount (and the close
    ///         factor) by less than one unit's discounted price; a small position is therefore
    ///         always liquidatable instead of seizing zero units.
    ///
    ///         When a call closes the debt completely, any collateral beyond the liquidator's
    ///         units stays credited to the position; the borrower takes it out with
    ///         {claimCollateral}. Neither it nor the surplus is pushed to the borrower here,
    ///         because a push to a frozen or no-longer-verified borrower would revert the whole
    ///         liquidation.
    /// @return debtRepaid Debt closed. The liquidator pays this plus any credited surplus.
    function liquidate(address borrower, uint256 maxRepayAmount)
        external
        nonReentrant
        returns (uint256 debtRepaid, uint256 collateralSeized)
    {
        _accrue();
        Position storage pos = positions[borrower];
        uint256 currentDebt = Math.mulDiv(pos.scaledDebt, borrowIndex, WAD);
        if (currentDebt == 0) revert NoOutstandingDebt();

        (uint256 pricePerUnit, uint256 units) = _liquidationUnits(pos, borrower, currentDebt, maxRepayAmount);
        uint256 payment;
        (debtRepaid, payment) = _applyLiquidationPayment(pos, borrower, currentDebt, units, pricePerUnit);
        collateralSeized = units;

        loanToken.safeTransferFrom(msg.sender, address(this), payment);
        collateralToken.safeTransfer(msg.sender, collateralSeized);
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

    /// @notice Loan-token cash available to borrowers and withdrawing lenders: the balance less
    ///         the liquidation surplus owed to borrowers.
    function availableLiquidity() external view returns (uint256) {
        return _availableCash();
    }

    /// @notice Pool utilization, WAD-scaled: outstanding debt / (outstanding debt + cash).
    function utilization() public view returns (uint256) {
        uint256 debt = Math.mulDiv(totalScaledDebt, borrowIndex, WAD);
        uint256 cash = _availableCash();
        uint256 total = debt + cash;
        if (total == 0) return 0;
        return Math.mulDiv(debt, WAD, total);
    }

    /// @notice Current annualized borrow rate, WAD-scaled.
    function borrowRate() external view returns (uint256) {
        return baseRateWad + Math.mulDiv(slopeWad, utilization(), WAD);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /// @dev Applies the health-factor and close-factor policy and returns the whole collateral
    ///      units this liquidation call sells. Kept separate from the accounting mutation so
    ///      rounding locals cannot exhaust the EVM stack in {liquidate}.
    function _liquidationUnits(Position storage pos, address borrower, uint256 currentDebt, uint256 maxRepayAmount)
        private
        view
        returns (uint256 pricePerUnit, uint256 units)
    {
        bool withinGracePeriod;
        (pricePerUnit, withinGracePeriod) = _currentPriceForLiquidation();
        uint256 factor = _healthFactorFor(pos.collateralAmount, pricePerUnit, currentDebt);
        uint256 threshold = withinGracePeriod
            ? Math.mulDiv(WAD, BPS_DENOMINATOR - STALE_GRACE_HEALTH_FACTOR_BUFFER_BPS, BPS_DENOMINATOR)
            : WAD;
        if (factor >= threshold) revert PositionHealthy(borrower);

        // Severity-scaled close factor (50%/100%). On a stale mark inside the grace window the
        // 50% cap always applies: the full close is reserved for a fresh mark, so the grace
        // regime stays the borrower safety margin it is documented as.
        uint256 effectiveCloseFactorBps = !withinGracePeriod && factor < FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD
            ? MAX_CLOSE_FACTOR_BPS
            : CLOSE_FACTOR_BPS;
        uint256 maxCloseable = Math.mulDiv(currentDebt, effectiveCloseFactorBps, BPS_DENOMINATOR);
        // Dust fallback: a close-factor result of zero may close the full remaining debt.
        if (maxCloseable == 0) {
            maxCloseable = currentDebt;
        }
        uint256 requestedRepay = maxRepayAmount < maxCloseable ? maxRepayAmount : maxCloseable;
        if (requestedRepay == 0) revert ZeroAmount();

        // Whole units, rounded up (so never zero), capped at the pledged collateral.
        units = Math.mulDiv(
            requestedRepay, BPS_DENOMINATOR + liquidationBonusBps, BPS_DENOMINATOR * pricePerUnit, Math.Rounding.Ceil
        );
        if (units > pos.collateralAmount) {
            units = pos.collateralAmount;
        }
    }

    /// @dev Sells `units` to the liquidator at the mark less the bonus. The payment reduces the
    ///      debt (conservative debt-share rounding); whatever the debt reduction leaves of the
    ///      payment is credited to the borrower's {surplusOf}. Collateral left over after a full
    ///      close stays in `pos.collateralAmount` for {claimCollateral} (see {liquidate}).
    function _applyLiquidationPayment(
        Position storage pos,
        address borrower,
        uint256 currentDebt,
        uint256 units,
        uint256 pricePerUnit
    ) private returns (uint256 debtRepaid, uint256 payment) {
        payment = Math.mulDiv(units * pricePerUnit, BPS_DENOMINATOR, BPS_DENOMINATOR + liquidationBonusBps, Math.Rounding.Ceil);
        (uint256 residualScaledDebt, uint256 actualRepayAmount) =
            _residualDebtAfterPayment(pos.scaledDebt, currentDebt, payment < currentDebt ? payment : currentDebt);
        debtRepaid = actualRepayAmount;

        uint256 scaledRepaid = pos.scaledDebt - residualScaledDebt;
        pos.scaledDebt = residualScaledDebt;
        totalScaledDebt -= scaledRepaid;
        pos.collateralAmount -= units;
        totalCollateral -= units;

        uint256 surplus = payment - debtRepaid;
        if (surplus > 0) {
            surplusOf[borrower] += surplus;
            totalSurplus += surplus;
            emit LiquidationSurplusCredited(borrower, surplus);
        }
    }

    /// @dev `lltv × (1 + bonus) ≤ 1 − maxDeviation`: a position just below health factor 1 that
    ///      then takes one full in-tolerance oracle move can still pay the liquidator's bonus
    ///      out of its own collateral, and a liquidation near health factor 1 always raises it.
    ///      The strict `lltv × (1 + bonus) < 1` is checked on its own so it also holds for an
    ///      oracle that opts out of the deviation check with `type(uint256).max`. Checked
    ///      against the cap in force at construction; `RegisterwerkNavOracle` can only lower it.
    function _checkLiquidationHaircut(uint256 lltvBps_, uint256 liquidationBonusBps_, uint256 maxDeviationBps_)
        private
        pure
    {
        uint256 incentiveAdjustedLltv = lltvBps_ * (BPS_DENOMINATOR + liquidationBonusBps_);
        if (incentiveAdjustedLltv >= BPS_DENOMINATOR * BPS_DENOMINATOR) revert InvalidLiquidationIncentive();
        if (maxDeviationBps_ == type(uint256).max) return;
        if (
            maxDeviationBps_ >= BPS_DENOMINATOR
                || incentiveAdjustedLltv > BPS_DENOMINATOR * (BPS_DENOMINATOR - maxDeviationBps_)
        ) revert InsufficientLiquidationHaircut();
    }

    /// @dev Pool cash less the liquidation surplus owed to borrowers.
    function _availableCash() private view returns (uint256) {
        uint256 balance = loanToken.balanceOf(address(this));
        return balance > totalSurplus ? balance - totalSurplus : 0;
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
