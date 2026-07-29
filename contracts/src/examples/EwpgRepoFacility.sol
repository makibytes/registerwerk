// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";

/// @title EwpgRepoFacility
/// @notice Reference marketplace dApp: a compliant repo / collateralized-lending facility —
///         the primary exit-liquidity mechanism for holders of Registerwerk-issued securities,
///         deliberately in place of a traditional order-book secondary market. Modeled on the
///         two dominant real-world patterns for turning an illiquid, NAV-priced instrument
///         into liquidity without selling it:
///           - TradFi repo / securities lending: pledge the bond, draw cash, keep the
///             position, repurchase later — the actual liquidity engine of bond markets,
///             far deeper than secondary trading.
///           - DeFi money markets (Aave/Compound): pooled stablecoin supply, algorithmic
///             utilization-based rates, permissionless liquidation of unhealthy positions.
///         See `docs/platform/defi-interoperability.md` for the full research/design
///         rationale, including why this is the right feature and an AMM/order-book is not.
///
/// @dev Two independently-gated sides, deliberately asymmetric:
///        - Lender side ({deposit}/{withdraw}) is **open to any stablecoin holder** — no
///          {RegisterwerkGated} check. Lenders only ever hold a claim on pooled stablecoin;
///          they never touch the restricted collateral asset, so there is no securities-law
///          reason to gate them. Widening this side as much as possible is exactly what
///          deepens the pool and attracts liquidity providers.
///        - Borrower side ({pledgeAndBorrow}) is gated by `repo-facility.borrow` + KYC,
///          since only a verified investor may pledge the restricted collateral token.
///          {repay} and {liquidate} are intentionally left ungated: the collateral transfer
///          back to the caller is itself subject to the token's own T-REX identity-registry
///          check, so an unverified caller's transaction simply reverts at the token layer —
///          no separate ecosystem permission check is needed to stay compliant, and gating
///          repayment would only add friction to reducing risk.
///
///      Collateral is pooled into this contract's own address across many borrowers exactly
///      like {CompliantSecondaryMarket} — the token's registry agent must flag this facility
///      via `EwpgComplianceModule.setNomineePool(token, address(this), true)` for any
///      ERC-3643 collateral asset, or every pledge past the first investor's cap would revert.
///
///      Interest accrual uses the standard index-based model (Aave-style
///      `liquidityIndex`/`borrowIndex`, WAD-scaled) so both sides settle in O(1) regardless
///      of participant count. This reference implementation keeps 100% of borrower interest
///      flowing to depositors (no protocol reserve cut) to keep the accounting exactly
///      auditable — a reserve factor is a natural, isolated extension for a production
///      deployment. Liquidation is full-close-factor only (an unhealthy position is repaid
///      in full by the liquidator in one call) for tractability; partial liquidation is a
///      possible future refinement.
contract EwpgRepoFacility is RegisterwerkGated, ReentrancyGuard {
    using SafeERC20 for IERC20;

    bytes32 public constant BORROW = keccak256("repo-facility.borrow");
    bytes32 public constant CONFIGURE = keccak256("repo-facility.configure");
    uint256 public constant TOPIC_KYC = 1;

    uint256 private constant WAD = 1e18;
    uint256 private constant BPS_DENOMINATOR = 10_000;
    uint256 private constant SECONDS_PER_YEAR = 365 days;

    /// @notice Annualized utilization-based borrow-rate model, WAD-scaled:
    ///         borrowRate = BASE_RATE_WAD + SLOPE_WAD * utilization.
    uint256 public constant BASE_RATE_WAD = 0.02e18; // 2% at zero utilization
    uint256 public constant SLOPE_WAD = 0.18e18; // up to +18% at 100% utilization

    /// @notice The stablecoin lenders supply and borrowers draw against pledged collateral.
    IERC20 public immutable paymentToken;

    struct CollateralConfig {
        uint256 pricePerUnit; // paymentToken base units per 1 collateral unit
        uint256 maxLtvBps; // max borrow at origination, e.g. 7000 = 70%
        uint256 liquidationThresholdBps; // health-factor threshold, e.g. 8000 = 80%
        uint256 liquidationBonusBps; // extra collateral awarded to the liquidator, e.g. 500 = 5%
        bool enabled;
    }

    struct Position {
        uint256 collateralAmount;
        uint256 scaledDebt; // actual debt = scaledDebt * borrowIndex / WAD
    }

    /// @notice collateral token => config. Set by the operator via {setCollateralConfig}.
    mapping(address => CollateralConfig) public collateralConfigs;

    /// @notice borrower => collateral token => position.
    mapping(address => mapping(address => Position)) public positions;

    uint256 public liquidityIndex = WAD;
    uint256 public borrowIndex = WAD;
    uint256 public totalScaledDeposits;
    uint256 public totalScaledDebt;
    uint256 public lastAccrualTimestamp;

    mapping(address => uint256) public scaledDepositOf;

    event Deposited(address indexed lender, uint256 amount, uint256 scaledAmount);
    event Withdrawn(address indexed lender, uint256 amount, uint256 scaledAmount);
    event CollateralConfigured(
        address indexed token,
        uint256 pricePerUnit,
        uint256 maxLtvBps,
        uint256 liquidationThresholdBps,
        uint256 liquidationBonusBps,
        bool enabled
    );
    event Borrowed(address indexed borrower, address indexed collateralToken, uint256 collateralAmount, uint256 borrowAmount);
    event Repaid(address indexed borrower, address indexed collateralToken, uint256 repayAmount, uint256 collateralReturned);
    event Liquidated(
        address indexed borrower,
        address indexed collateralToken,
        address indexed liquidator,
        uint256 debtRepaid,
        uint256 collateralSeized
    );

    error ZeroAddress();
    error ZeroAmount();
    error InvalidThresholds();
    error CollateralNotEnabled(address token);
    error InsufficientPoolLiquidity();
    error ExceedsMaxLtv();
    error PositionHealthy(address borrower, address collateralToken);
    error NoOutstandingDebt();
    error InsufficientShares();

    constructor(IPermissionOracle oracle_, IERC20 paymentToken_) RegisterwerkGated(oracle_) {
        if (address(paymentToken_) == address(0)) revert ZeroAddress();
        paymentToken = paymentToken_;
        lastAccrualTimestamp = block.timestamp;
    }

    // ── Lender side — open to any stablecoin holder ─────────────────────────

    /// @notice Supplies `amount` of {paymentToken} to the pool, minted as index-scaled shares.
    function deposit(uint256 amount) external nonReentrant returns (uint256 scaledAmount) {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        scaledAmount = (amount * WAD) / liquidityIndex;
        scaledDepositOf[msg.sender] += scaledAmount;
        totalScaledDeposits += scaledAmount;
        paymentToken.safeTransferFrom(msg.sender, address(this), amount);
        emit Deposited(msg.sender, amount, scaledAmount);
    }

    /// @notice Withdraws up to `amount` of {paymentToken}, limited by the caller's current
    ///         claim and by the pool's available (unborrowed) cash.
    function withdraw(uint256 amount) external nonReentrant returns (uint256 scaledAmount) {
        if (amount == 0) revert ZeroAmount();
        _accrue();
        scaledAmount = (amount * WAD) / liquidityIndex;
        if (scaledAmount > scaledDepositOf[msg.sender]) revert InsufficientShares();
        if (amount > paymentToken.balanceOf(address(this))) revert InsufficientPoolLiquidity();
        scaledDepositOf[msg.sender] -= scaledAmount;
        totalScaledDeposits -= scaledAmount;
        paymentToken.safeTransfer(msg.sender, amount);
        emit Withdrawn(msg.sender, amount, scaledAmount);
    }

    /// @notice Current claim of `lender` in {paymentToken} base units.
    function balanceOf(address lender) external view returns (uint256) {
        (uint256 projectedLiquidityIndex,) = _pendingIndices();
        return (scaledDepositOf[lender] * projectedLiquidityIndex) / WAD;
    }

    // ── Operator configuration ───────────────────────────────────────────────

    /// @notice Enables (or updates) a collateral asset. `maxLtvBps` must be strictly below
    ///         `liquidationThresholdBps`, which must not exceed 100%, so a freshly-originated
    ///         position always starts healthy with room before liquidation.
    function setCollateralConfig(
        address token,
        uint256 pricePerUnit,
        uint256 maxLtvBps,
        uint256 liquidationThresholdBps,
        uint256 liquidationBonusBps,
        bool enabled
    ) external requiresPermission(CONFIGURE) {
        if (token == address(0)) revert ZeroAddress();
        if (enabled && pricePerUnit == 0) revert ZeroAmount();
        if (maxLtvBps >= liquidationThresholdBps || liquidationThresholdBps > BPS_DENOMINATOR) {
            revert InvalidThresholds();
        }
        collateralConfigs[token] =
            CollateralConfig(pricePerUnit, maxLtvBps, liquidationThresholdBps, liquidationBonusBps, enabled);
        emit CollateralConfigured(token, pricePerUnit, maxLtvBps, liquidationThresholdBps, liquidationBonusBps, enabled);
    }

    /// @notice Pushes a fresh price mark for an already-enabled collateral asset — e.g. from
    ///         an operator NAV feed or the last executed fill on a {CompliantSecondaryMarket}
    ///         desk trading the same token.
    function updatePrice(address token, uint256 pricePerUnit) external requiresPermission(CONFIGURE) {
        CollateralConfig storage cfg = collateralConfigs[token];
        if (!cfg.enabled) revert CollateralNotEnabled(token);
        if (pricePerUnit == 0) revert ZeroAmount();
        cfg.pricePerUnit = pricePerUnit;
        emit CollateralConfigured(
            token, pricePerUnit, cfg.maxLtvBps, cfg.liquidationThresholdBps, cfg.liquidationBonusBps, cfg.enabled
        );
    }

    // ── Borrower side — gated: only verified investors may pledge & borrow ──

    /// @notice Pledges `collateralAmount` of `collateralToken` and borrows up to the
    ///         configured LTV against the combined (existing + new) position. Reverts at the
    ///         T-REX layer if this facility is not a verified, nominee-flagged holder of
    ///         `collateralToken` on the token's own compliance module.
    function pledgeAndBorrow(address collateralToken, uint256 collateralAmount, uint256 borrowAmount)
        external
        nonReentrant
        requiresPermission(BORROW)
        requiresClaim(TOPIC_KYC)
    {
        if (collateralAmount == 0 || borrowAmount == 0) revert ZeroAmount();
        CollateralConfig storage cfg = collateralConfigs[collateralToken];
        if (!cfg.enabled) revert CollateralNotEnabled(collateralToken);
        _accrue();

        Position storage pos = positions[msg.sender][collateralToken];
        uint256 currentDebt = (pos.scaledDebt * borrowIndex) / WAD;
        uint256 newCollateral = pos.collateralAmount + collateralAmount;
        uint256 newDebt = currentDebt + borrowAmount;
        uint256 collateralValue = newCollateral * cfg.pricePerUnit;
        if (newDebt * BPS_DENOMINATOR > collateralValue * cfg.maxLtvBps) revert ExceedsMaxLtv();
        if (borrowAmount > paymentToken.balanceOf(address(this))) revert InsufficientPoolLiquidity();

        IERC20(collateralToken).safeTransferFrom(msg.sender, address(this), collateralAmount);

        pos.collateralAmount = newCollateral;
        uint256 addedScaledDebt = (borrowAmount * WAD) / borrowIndex;
        pos.scaledDebt += addedScaledDebt;
        totalScaledDebt += addedScaledDebt;

        paymentToken.safeTransfer(msg.sender, borrowAmount);
        emit Borrowed(msg.sender, collateralToken, collateralAmount, borrowAmount);
    }

    /// @notice Repays up to `repayAmount` (capped to the current outstanding debt) and
    ///         releases a proportional share of the pledged collateral. Not gated by
    ///         {RegisterwerkGated} — see the contract-level NatSpec for why.
    function repay(address collateralToken, uint256 repayAmount)
        external
        nonReentrant
        returns (uint256 collateralReturned)
    {
        _accrue();
        Position storage pos = positions[msg.sender][collateralToken];
        uint256 currentDebt = (pos.scaledDebt * borrowIndex) / WAD;
        if (currentDebt == 0) revert NoOutstandingDebt();
        if (repayAmount > currentDebt) {
            repayAmount = currentDebt;
        }

        uint256 collateralBefore = pos.collateralAmount;
        collateralReturned = (collateralBefore * repayAmount) / currentDebt;

        uint256 scaledRepaid = (repayAmount * WAD) / borrowIndex;
        if (scaledRepaid > pos.scaledDebt) {
            scaledRepaid = pos.scaledDebt;
        }
        pos.scaledDebt -= scaledRepaid;
        totalScaledDebt -= scaledRepaid;
        pos.collateralAmount = collateralBefore - collateralReturned;

        paymentToken.safeTransferFrom(msg.sender, address(this), repayAmount);
        IERC20(collateralToken).safeTransfer(msg.sender, collateralReturned);
        emit Repaid(msg.sender, collateralToken, repayAmount, collateralReturned);
    }

    /// @notice Permissionless liquidation of an under-collateralized position (health factor
    ///         below 1.0): the caller repays the position's full outstanding debt and
    ///         receives its collateral plus the configured liquidation bonus, capped at the
    ///         collateral actually held. Not gated by {RegisterwerkGated} — see the
    ///         contract-level NatSpec for why an unverified caller cannot actually succeed.
    function liquidate(address borrower, address collateralToken)
        external
        nonReentrant
        returns (uint256 debtRepaid, uint256 collateralSeized)
    {
        _accrue();
        Position storage pos = positions[borrower][collateralToken];
        uint256 currentDebt = (pos.scaledDebt * borrowIndex) / WAD;
        if (currentDebt == 0) revert NoOutstandingDebt();

        CollateralConfig storage cfg = collateralConfigs[collateralToken];
        if (_healthFactorFor(pos.collateralAmount, cfg, currentDebt) >= WAD) {
            revert PositionHealthy(borrower, collateralToken);
        }

        debtRepaid = currentDebt;
        uint256 seizeValue = (debtRepaid * (BPS_DENOMINATOR + cfg.liquidationBonusBps)) / BPS_DENOMINATOR;
        collateralSeized = seizeValue / cfg.pricePerUnit;
        if (collateralSeized > pos.collateralAmount) {
            collateralSeized = pos.collateralAmount;
        }

        totalScaledDebt -= pos.scaledDebt;
        pos.scaledDebt = 0;
        pos.collateralAmount -= collateralSeized;

        paymentToken.safeTransferFrom(msg.sender, address(this), debtRepaid);
        IERC20(collateralToken).safeTransfer(msg.sender, collateralSeized);
        emit Liquidated(borrower, collateralToken, msg.sender, debtRepaid, collateralSeized);
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /// @notice Current outstanding debt of `borrower` against `collateralToken`.
    function debtOf(address borrower, address collateralToken) external view returns (uint256) {
        (, uint256 projectedBorrowIndex) = _pendingIndices();
        return (positions[borrower][collateralToken].scaledDebt * projectedBorrowIndex) / WAD;
    }

    /// @notice Health factor, WAD-scaled (>= 1e18 is healthy, < 1e18 is liquidatable).
    ///         `type(uint256).max` when there is no outstanding debt.
    function healthFactor(address borrower, address collateralToken) external view returns (uint256) {
        (, uint256 projectedBorrowIndex) = _pendingIndices();
        Position storage pos = positions[borrower][collateralToken];
        uint256 debt = (pos.scaledDebt * projectedBorrowIndex) / WAD;
        return _healthFactorFor(pos.collateralAmount, collateralConfigs[collateralToken], debt);
    }

    /// @notice Pool utilization, WAD-scaled: outstanding debt / (outstanding debt + cash).
    function utilization() public view returns (uint256) {
        uint256 debt = (totalScaledDebt * borrowIndex) / WAD;
        uint256 cash = paymentToken.balanceOf(address(this));
        uint256 total = debt + cash;
        if (total == 0) return 0;
        return (debt * WAD) / total;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    function _healthFactorFor(uint256 collateralAmount, CollateralConfig storage cfg, uint256 debt)
        private
        view
        returns (uint256)
    {
        if (debt == 0) return type(uint256).max;
        uint256 collateralValue = collateralAmount * cfg.pricePerUnit;
        uint256 adjustedValue = (collateralValue * cfg.liquidationThresholdBps) / BPS_DENOMINATOR;
        return (adjustedValue * WAD) / debt;
    }

    /// @dev Accrues interest for the elapsed period at the utilization observed at the start
    ///      of the period, then rolls `lastAccrualTimestamp` forward — standard discrete-rate
    ///      index update, O(1) regardless of participant count.
    function _accrue() private {
        uint256 timeDelta = block.timestamp - lastAccrualTimestamp;
        if (timeDelta == 0) return;
        lastAccrualTimestamp = block.timestamp;
        if (totalScaledDebt == 0) return;

        uint256 util = utilization();
        uint256 borrowRate = BASE_RATE_WAD + (SLOPE_WAD * util) / WAD;
        uint256 borrowGrowth = (borrowRate * timeDelta) / SECONDS_PER_YEAR;
        borrowIndex += (borrowIndex * borrowGrowth) / WAD;

        // 100% of borrower interest flows to depositors — see contract-level NatSpec.
        uint256 supplyRate = (borrowRate * util) / WAD;
        uint256 supplyGrowth = (supplyRate * timeDelta) / SECONDS_PER_YEAR;
        liquidityIndex += (liquidityIndex * supplyGrowth) / WAD;
    }

    /// @dev View-only projection of what {_accrue} would do, without mutating state.
    function _pendingIndices() private view returns (uint256 projectedLiquidityIndex, uint256 projectedBorrowIndex) {
        uint256 timeDelta = block.timestamp - lastAccrualTimestamp;
        if (timeDelta == 0 || totalScaledDebt == 0) {
            return (liquidityIndex, borrowIndex);
        }
        uint256 util = utilization();
        uint256 borrowRate = BASE_RATE_WAD + (SLOPE_WAD * util) / WAD;
        uint256 borrowGrowth = (borrowRate * timeDelta) / SECONDS_PER_YEAR;
        projectedBorrowIndex = borrowIndex + (borrowIndex * borrowGrowth) / WAD;

        uint256 supplyRate = (borrowRate * util) / WAD;
        uint256 supplyGrowth = (supplyRate * timeDelta) / SECONDS_PER_YEAR;
        projectedLiquidityIndex = liquidityIndex + (liquidityIndex * supplyGrowth) / WAD;
    }
}
