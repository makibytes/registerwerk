// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@erc3643/ERC-3643/IERC3643.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/token/ERC20/extensions/IERC20Permit.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";

/// @title EwpgBondDesk
/// @notice Reference marketplace dApp: a paying agent for an eWpG-registered bond issued
///         as a full ERC-3643 (T-REX) security token, settling every cash leg in a
///         operator-configured payment token such as AllUnity Euro (AUEUR) or USDC. The contract
///         does not verify MiCAR classification, issuer authorisation, or redemption terms.
///         Deploy the bond via {EwpgTREXFactory.deployEwpgSuite}, add this desk
///         as a T-REX agent on the resulting token, then deploy the desk itself pointing
///         at the bond, the payment token, the issuer treasury, and the ecosystem
///         {PermissionOracle}.
///
/// @dev This contract demonstrates the two authority layers a real eWpG instrument
///      stacks on Registerwerk:
///        1. ERC-3643 agent authority — *what the desk contract itself may do to the
///           token* (mint/burn), granted once by the token owner via
///           `AgentRole(bond).addAgent(address(desk))`. This is standard T-REX access
///           control and has nothing to do with the ecosystem permission framework.
///        2. Ecosystem permission authority — *which org/human may trigger the desk*,
///           enforced entirely through {RegisterwerkGated}: org membership, the
///           `PermissionOracle`, and ONCHAINID claim topics. The desk never touches
///           ONCHAINID or the permission registries directly.
///
///      Permission surface (namespace "bond-desk."), matching the manifest shipped in
///      `backend/src/main/resources/demo/dapps/bond-desk.manifest.json`:
///        - bond-desk.issue       : sell new units to a KYC'd investor against payment
///        - bond-desk.pay-coupon  : pay the current coupon period per holder
///        - bond-desk.redeem      : pay principal and burn a matured position (AML re-checked)
///
///      Payment leg — every cash flow is a real on-chain stablecoin transfer, declared
///      as payment rails in the marketplace manifest:
///        - {subscribe} is primary-market delivery-versus-payment: the investor's
///          subscription price moves investor → treasury and the bond mints to the
///          investor atomically in one transaction (no escrow needed — mint IS the
///          delivery). Requires the investor's prior ERC-20 approval to this desk.
///        - {payCoupon} and {redeem} pay treasury → holder; the treasury pre-approves
///          this desk on the payment token. Coupon periods are time-gated and paid from a
///          record-date balance snapshot taken at period-open, not a live balance, so
///          transferring units mid-period cannot collect the same coupon twice under a
///          second address (see {payCoupon} for the snapshot mechanics).
///      Secondary-market DvP between investors is out of the desk's scope; that is what
///      the operator's {DvpSettlement} rail (`erc7573-dvp`) provides.
contract EwpgBondDesk is RegisterwerkGated {
    using SafeERC20 for IERC20;

    bytes32 public constant ISSUE = keccak256("bond-desk.issue");
    bytes32 public constant PAY_COUPON = keccak256("bond-desk.pay-coupon");
    bytes32 public constant REDEEM = keccak256("bond-desk.redeem");
    bytes32 public constant PAUSE = keccak256("bond-desk.pause");

    uint256 public constant TOPIC_KYC = 1;
    uint256 public constant TOPIC_AML = 2;

    /// @notice The ERC-3643 (T-REX) bond token this desk administers. The desk must hold
    ///         T-REX agent rights on this token (see contract-level NatSpec) — that is
    ///         configured externally, not by this contract.
    IERC3643 public immutable bond;

    /// @notice The MiCAR EMT stablecoin every cash leg settles in (e.g. AUEUR, USDC).
    IERC20 public immutable paymentToken;

    /// @notice Issuer treasury: receives subscription proceeds and funds coupon and
    ///         redemption payouts. Must hold a standing ERC-20 approval for this desk.
    address public immutable treasury;

    /// @notice Face value per bond unit in payment-token base units (e.g. 100_000_000
    ///         = 100.00 EUR face value in a 6-decimals stablecoin).
    uint256 public immutable pricePerUnit;

    /// @notice Coupon per period in basis points of face value (e.g. 450 = 4.50%).
    uint16 public immutable couponRateBps;

    /// @notice Length of one coupon period in seconds.
    uint256 public immutable couponIntervalSecs;

    /// @notice Unix timestamp at/after which {redeem} may be called.
    uint256 public immutable maturityTimestamp;

    /// @notice Number of coupon periods opened so far.
    uint256 public couponPeriod;

    /// @notice Timestamp at/after which the next coupon period may be opened.
    uint256 public nextCouponDue;

    /// @notice Whether a holder has been paid for a given coupon period.
    mapping(uint256 => mapping(address => bool)) public couponPaid;

    /// @notice Whether the record-date snapshot has opened for a period yet — taken from
    ///         the very first {payCoupon} call after the period becomes due.
    mapping(uint256 => bool) public periodOpened;

    /// @notice Each holder's bond balance frozen at the record-date snapshot for a period.
    ///         Coupons are paid from this value, never live {IERC3643-balanceOf}, so units
    ///         transferred after the period opens cannot be paid twice under two different
    ///         addresses within the same period.
    mapping(uint256 => mapping(address => uint256)) public periodOpenBalance;

    /// @notice Whether a holder's balance has already been snapshotted for a period.
    mapping(uint256 => mapping(address => bool)) public periodBalanceSnapshotted;

    /// @notice Whether a holder has already redeemed their matured position.
    mapping(address => bool) public redeemed;

    /// @notice Circuit breaker for every cash-leg function ({subscribe}, {payCoupon},
    ///         {redeem}). Distinct from pausing the bond token itself: this stops the
    ///         desk's payment leg specifically, e.g. when the operator disables the
    ///         payment rail this instance settles in (its {paymentToken} address is
    ///         immutable, so the desk cannot simply be redeployed onto a new rail).
    bool public paused;

    event BondSubscribed(address indexed investor, uint256 amount, uint256 paid);
    event CouponPaid(uint256 indexed period, address indexed holder, uint256 amount);
    event BondRedeemed(address indexed holder, uint256 amount, uint256 principal);
    event DeskPaused(address indexed by);
    event DeskUnpaused(address indexed by);

    error ZeroAmount();
    error ZeroAddress();
    error BondNotMatured(uint256 maturityTimestamp);
    error AlreadyRedeemed(address holder);
    error NoCouponPeriodOpen(uint256 nextCouponDue);
    error DeskIsPaused();

    constructor(
        IPermissionOracle oracle_,
        IERC3643 bond_,
        IERC20 paymentToken_,
        address treasury_,
        uint256 pricePerUnit_,
        uint16 couponRateBps_,
        uint256 couponIntervalSecs_,
        uint256 maturityTimestamp_
    ) RegisterwerkGated(oracle_) {
        if (address(bond_) == address(0) || address(paymentToken_) == address(0) || treasury_ == address(0)) {
            revert ZeroAddress();
        }
        if (pricePerUnit_ == 0 || couponIntervalSecs_ == 0) revert ZeroAmount();
        bond = bond_;
        paymentToken = paymentToken_;
        treasury = treasury_;
        pricePerUnit = pricePerUnit_;
        couponRateBps = couponRateBps_;
        couponIntervalSecs = couponIntervalSecs_;
        maturityTimestamp = maturityTimestamp_;
        nextCouponDue = block.timestamp + couponIntervalSecs_;
    }

    modifier whenNotPaused() {
        if (paused) revert DeskIsPaused();
        _;
    }

    /// @notice Suspends {subscribe}, {subscribeWithPermit}, {payCoupon} and {redeem} — e.g.
    ///         when the payment rail this desk settles in (its immutable {paymentToken}) is
    ///         disabled at the catalog level and can no longer be relied on to move funds.
    function pause() external requiresPermission(PAUSE) {
        paused = true;
        emit DeskPaused(msg.sender);
    }

    /// @notice Resumes normal operation.
    function unpause() external requiresPermission(PAUSE) {
        paused = false;
        emit DeskUnpaused(msg.sender);
    }

    /// @notice Primary-market subscription: pulls `amount * pricePerUnit` of the payment
    ///         token from the investor into the issuer treasury and mints `amount` bond
    ///         units to the investor — both in the same transaction, so delivery and
    ///         payment cannot come apart. Reverts at the T-REX layer if the investor's
    ///         identity is not registered/verified on the bond's identity registry, or
    ///         if compliance rejects the mint (e.g. blocked country, max-investor cap) —
    ///         those checks are independent of, and in addition to, the ecosystem gating
    ///         below. The investor must have approved this desk on the payment token.
    function subscribe(address investor, uint256 amount)
        external
        requiresPermission(ISSUE)
        requiresClaim(TOPIC_KYC)
        whenNotPaused
    {
        _subscribe(investor, amount);
    }

    /// @notice Same as {subscribe}, but spends a signed EIP-2612 `permit` instead of
    ///         requiring a separate prior `approve` transaction — halves the transaction
    ///         count for investors, and pairs naturally with a sponsored (gasless)
    ///         transaction (see `docs/platform/account-abstraction.md`). Reverts if
    ///         {paymentToken} does not implement EIP-2612 (not every configured rail does —
    ///         check before wiring this up for a given deployment).
    function subscribeWithPermit(
        address investor,
        uint256 amount,
        uint256 deadline,
        uint8 v,
        bytes32 r,
        bytes32 s
    ) external requiresPermission(ISSUE) requiresClaim(TOPIC_KYC) whenNotPaused {
        IERC20Permit(address(paymentToken)).permit(investor, address(this), amount * pricePerUnit, deadline, v, r, s);
        _subscribe(investor, amount);
    }

    function _subscribe(address investor, uint256 amount) private {
        if (amount == 0) revert ZeroAmount();
        uint256 cost = amount * pricePerUnit;
        paymentToken.safeTransferFrom(investor, treasury, cost);
        bond.mint(investor, amount);
        emit BondSubscribed(investor, amount, cost);
    }

    /// @notice Pay the current coupon period to the given holders in the payment token,
    ///         funded from the issuer treasury. Opens the next period when it is due;
    ///         reverts while no period is open yet.
    ///
    ///         Record date: the very first call after a period becomes due snapshots every
    ///         listed holder's bond balance at that moment — all subsequent payouts for
    ///         this period, in this call or any later one, pay from that frozen balance,
    ///         never a live {IERC3643-balanceOf} read. This is what makes each (period,
    ///         holder) payable at most once actually safe: without it, a holder paid in
    ///         this call could transfer their units to a second address and collect the
    ///         same period's coupon again there once that address is (eventually) included
    ///         in a later call — the frozen snapshot means a transfer after the record date
    ///         moves the *tokens*, not the *coupon entitlement*.
    ///
    ///         Consequence: only holders included in the period-opening call are eligible
    ///         for that period's coupon at all — the opening call must carry the complete
    ///         holder list known at that moment (the backend's indexed holder set). Anyone
    ///         first passed in a *later* call within the same period is skipped, since their
    ///         current balance could already reflect an intra-period transfer; missed
    ///         holders are a data/process issue for the operator to correct out of band
    ///         (e.g. via the next period, or a manual register correction), not something
    ///         this contract can safely backfill from a live balance.
    function payCoupon(address[] calldata holders)
        external
        requiresPermission(PAY_COUPON)
        requiresClaim(TOPIC_KYC)
        whenNotPaused
        returns (uint256 period)
    {
        if (block.timestamp >= nextCouponDue) {
            couponPeriod += 1;
            nextCouponDue += couponIntervalSecs;
        }
        period = couponPeriod;
        if (period == 0) revert NoCouponPeriodOpen(nextCouponDue);

        bool openingThisCall = !periodOpened[period];
        if (openingThisCall) {
            periodOpened[period] = true;
        }

        for (uint256 i = 0; i < holders.length; i++) {
            address holder = holders[i];
            if (couponPaid[period][holder]) {
                continue;
            }
            if (!periodBalanceSnapshotted[period][holder]) {
                if (!openingThisCall) {
                    // Not part of the record-date snapshot — their current balance may
                    // already reflect a same-period transfer; skip rather than risk a
                    // double-pay of the same underlying units.
                    continue;
                }
                periodOpenBalance[period][holder] = bond.balanceOf(holder);
                periodBalanceSnapshotted[period][holder] = true;
            }
            uint256 balance = periodOpenBalance[period][holder];
            if (balance == 0) {
                continue;
            }
            couponPaid[period][holder] = true;
            uint256 amount = (balance * pricePerUnit * couponRateBps) / 10_000;
            paymentToken.safeTransferFrom(treasury, holder, amount);
            emit CouponPaid(period, holder, amount);
        }
    }

    /// @notice Pay out a matured holder's principal from the treasury and burn their
    ///         full position — payment and delivery of the final leg stay atomic. Gated
    ///         by the AML topic rather than KYC: redemption is a payout event, so the
    ///         desk re-checks the stricter of the two claim topics independently of
    ///         whichever topic gated the issuance.
    function redeem(address holder) external requiresPermission(REDEEM) requiresClaim(TOPIC_AML) whenNotPaused {
        if (block.timestamp < maturityTimestamp) revert BondNotMatured(maturityTimestamp);
        if (redeemed[holder]) revert AlreadyRedeemed(holder);

        uint256 balance = bond.balanceOf(holder);
        redeemed[holder] = true;
        uint256 principal = balance * pricePerUnit;
        if (balance > 0) {
            paymentToken.safeTransferFrom(treasury, holder, principal);
            bond.burn(holder, balance);
        }
        emit BondRedeemed(holder, balance, principal);
    }
}
