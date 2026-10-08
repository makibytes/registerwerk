// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@erc3643/ERC-3643/IERC3643.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/token/ERC20/extensions/IERC20Permit.sol";
import "@openzeppelin/contracts/utils/math/Math.sol";
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
///        - bond-desk.pause       : suspend / resume the desk's cash leg
///        - bond-desk.legal-order : release a withheld coupon / force-redeem a frozen holder
///                                  to a destination named by a legal order (a holder whose
///                                  hold has lifted claims its own withheld coupon, see
///                                  {claimWithheldCoupon}; that needs no permission)
///      Every one of them is additionally bound to this instance's {operatorOrg}: another
///      org holding the same `bond-desk.*` grant cannot operate this issuer's desk.
///
///      Coupon schedule: anchored on {maturityTimestamp}, not on the deployment second.
///      Period k falls due at `maturity - (finalCouponPeriod - k) * couponIntervalSecs`, with
///      `finalCouponPeriod = ceil((maturity - deployment) / couponIntervalSecs)`, so the
///      final period is due exactly at maturity and no period opens after it. When the term
///      is not a whole number of intervals (always the case for a scripted deployment, whose
///      transaction is mined a few seconds after the script computed maturity), the first
///      period is a short stub (`firstPeriodSecs`, counted from {couponStart}, the deployment
///      second) and is PRO-RATED by day count: the coupon is the period coupon times
///      `firstPeriodSecs / couponIntervalSecs` — ICMA actual/actual for an irregular first
///      period, with the regular interval as the notional period. Days are counted to the
///      second, so a deployment mined seconds late shaves seconds, not a whole day; for a
///      stub of whole days it equals the backend's calendar-day ACT/ACT-ICMA fraction. Every
///      later period pays the full coupon, so a maturity on the interval grid of the intended
///      start gives no stub at all. {couponAmount} is the single formula (rounded down once).
///      {redeem} requires the final period to have been opened first — call {payCoupon}
///      for the final period, then {redeem} — so a redemption can never burn a position
///      before its last coupon is snapshotted.
///
///      Frozen holders: the desk withholds only what is frozen. If the whole address is
///      frozen, the holder's entire coupon is withheld; if only some units are frozen, the
///      share of those units (`min(frozen units, snapshot)` of the snapshot balance) is
///      withheld and the rest is paid at once, so one frozen unit no longer blocks the whole
///      coupon. A withheld amount is moved from the treasury into the desk's own escrow
///      ({withheld}, {totalWithheld}) so it stays backed and cannot be spent or un-approved
///      before it is paid; `paid + withheld` is always exactly the coupon, so nothing is
///      lost or paid twice. It leaves escrow in exactly one of two ways: the holder (or anyone
///      on its behalf) calls {claimWithheldCoupon} once the holder has no frozen units left —
///      the cash goes to the holder, never elsewhere — or a legal order directs it to a named
///      destination ({releaseWithheld}); {forceRedeem} likewise serves a frozen position's
///      principal. {redeem} reverts for a frozen holder and {subscribe} refuses a frozen
///      investor. This is the same hold-in-place default the ERC-7540 vault applies to a
///      frozen owner's escrow.
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
    bytes32 public constant LEGAL_ORDER = keccak256("bond-desk.legal-order");

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

    /// @notice Index of the last coupon period — the one due at {maturityTimestamp}.
    ///         {payCoupon} never opens a period beyond it.
    uint256 public immutable finalCouponPeriod;

    /// @notice Start of the first coupon period (the deployment second): the issue date the
    ///         first period's accrual is counted from.
    uint256 public immutable couponStart;

    /// @notice Length in seconds of the first coupon period: `couponIntervalSecs` for a term
    ///         on the interval grid, shorter (a stub) otherwise; always in `(0, couponIntervalSecs]`.
    ///         Period 1's coupon is pro-rated by `firstPeriodSecs / couponIntervalSecs`.
    uint256 public immutable firstPeriodSecs;

    /// @notice The org operating this instance (the issuer / its paying agent). Every
    ///         privileged function requires the caller's wallet to be bound to this org.
    address public immutable operatorOrg;

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

    /// @notice Coupon withheld from a holder's frozen units for a period (payment-token base
    ///         units), held in the desk's own escrow. It leaves only through
    ///         {claimWithheldCoupon} (to the holder, once nothing is frozen) or
    ///         {releaseWithheld} (to the destination a legal order names); {payCoupon} never
    ///         pays a (period, holder) with a withheld amount a second time.
    mapping(uint256 => mapping(address => uint256)) public withheld;

    /// @notice Sum of all {withheld} amounts — what the desk holds in escrow.
    uint256 public totalWithheld;

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
    /// @dev `amount` is the withheld (escrowed) share only; the paid share is the period's
    ///      {CouponPaid} event for the same holder.
    event CouponWithheld(uint256 indexed period, address indexed holder, uint256 amount);
    event WithheldCouponClaimed(uint256 indexed period, address indexed holder, uint256 amount);
    event WithheldReleased(
        uint256 indexed period, address indexed holder, address indexed to, uint256 amount, string legalBasis
    );
    event ForcedRedemption(
        address indexed holder, address indexed to, uint256 amount, uint256 principal, string legalBasis
    );

    error ZeroAmount();
    error ZeroAddress();
    error BondNotMatured(uint256 maturityTimestamp);
    error AlreadyRedeemed(address holder);
    error NoCouponPeriodOpen(uint256 nextCouponDue);
    error DeskIsPaused();
    error InvalidCouponSchedule();
    error FinalCouponNotOpened(uint256 finalCouponPeriod);
    error HolderFrozen(address holder);
    error HolderNotFrozen(address holder);
    error NothingWithheld(uint256 period, address holder);
    error LegalBasisRequired();

    constructor(
        IPermissionOracle oracle_,
        IERC3643 bond_,
        IERC20 paymentToken_,
        address treasury_,
        uint256 pricePerUnit_,
        uint16 couponRateBps_,
        uint256 couponIntervalSecs_,
        uint256 maturityTimestamp_,
        address operatorOrg_
    ) RegisterwerkGated(oracle_) {
        if (address(bond_) == address(0) || address(paymentToken_) == address(0) || treasury_ == address(0)) {
            revert ZeroAddress();
        }
        if (pricePerUnit_ == 0 || couponIntervalSecs_ == 0) revert ZeroAmount();
        _requireOrg(operatorOrg_);
        // Anchor the schedule on maturity: the final period is due exactly at maturity and
        // the first one may be a short stub. Requiring an exact multiple of the interval
        // instead made the desk undeployable on a live chain (the mined block's timestamp
        // never equals the one the deploy script computed maturity from).
        if (maturityTimestamp_ <= block.timestamp) revert InvalidCouponSchedule();
        uint256 term = maturityTimestamp_ - block.timestamp;
        uint256 periods = (term + couponIntervalSecs_ - 1) / couponIntervalSecs_;
        finalCouponPeriod = periods;
        // The first period runs from now to `maturity - (periods - 1) * interval`; by the
        // ceiling above that is in (0, interval]. Re-checked so an arithmetic slip can never
        // turn the pro-rating into 0 or into more than a full coupon.
        uint256 stub = term - (periods - 1) * couponIntervalSecs_;
        if (stub == 0 || stub > couponIntervalSecs_) revert InvalidCouponSchedule();
        couponStart = block.timestamp;
        firstPeriodSecs = stub;
        operatorOrg = operatorOrg_;
        bond = bond_;
        paymentToken = paymentToken_;
        treasury = treasury_;
        pricePerUnit = pricePerUnit_;
        couponRateBps = couponRateBps_;
        couponIntervalSecs = couponIntervalSecs_;
        maturityTimestamp = maturityTimestamp_;
        nextCouponDue = maturityTimestamp_ - (periods - 1) * couponIntervalSecs_;
    }

    modifier whenNotPaused() {
        if (paused) revert DeskIsPaused();
        _;
    }

    /// @notice Suspends {subscribe}, {subscribeWithPermit}, {payCoupon} and {redeem} — e.g.
    ///         when the payment rail this desk settles in (its immutable {paymentToken}) is
    ///         disabled at the catalog level and can no longer be relied on to move funds.
    function pause() external requiresOrgPermission(operatorOrg, PAUSE) {
        paused = true;
        emit DeskPaused(msg.sender);
    }

    /// @notice Resumes normal operation.
    function unpause() external requiresOrgPermission(operatorOrg, PAUSE) {
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
    ///         Reverts {HolderFrozen} for a frozen investor: T-REX `mint` checks identity
    ///         and compliance, not the address freeze.
    function subscribe(address investor, uint256 amount)
        external
        requiresOrgPermission(operatorOrg, ISSUE)
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
    ) external requiresOrgPermission(operatorOrg, ISSUE) requiresClaim(TOPIC_KYC) whenNotPaused {
        IERC20Permit(address(paymentToken)).permit(investor, address(this), amount * pricePerUnit, deadline, v, r, s);
        _subscribe(investor, amount);
    }

    function _subscribe(address investor, uint256 amount) private {
        if (amount == 0) revert ZeroAmount();
        if (bond.isFrozen(investor)) revert HolderFrozen(investor);
        uint256 cost = amount * pricePerUnit;
        // The pull is authorised by the investor's own ERC-20 allowance (or EIP-2612 permit) to
        // this desk; the proceeds can only go to the immutable treasury and the bond mints to the
        // same investor. See "Payment leg" in the contract header.
        // slither-disable-next-line arbitrary-send-erc20
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
    ///
    ///         No period opens beyond {finalCouponPeriod}: once it is open, later calls keep
    ///         paying that final period only. Each coupon is {couponAmount} (period 1 is
    ///         pro-rated if it is a stub). A holder's frozen units' share is withheld into the
    ///         desk's escrow and the remainder paid (see {claimWithheldCoupon},
    ///         {releaseWithheld}); an address-frozen holder has the whole coupon withheld.
    function payCoupon(address[] calldata holders)
        external
        requiresOrgPermission(operatorOrg, PAY_COUPON)
        requiresClaim(TOPIC_KYC)
        whenNotPaused
        returns (uint256 period)
    {
        if (block.timestamp >= nextCouponDue && couponPeriod < finalCouponPeriod) {
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
            if (couponPaid[period][holder] || withheld[period][holder] != 0) {
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
            _settleCoupon(period, holder, periodOpenBalance[period][holder]);
        }
    }

    /// @notice The coupon for `units` bond units in coupon period `period`, in payment-token base
    ///         units: `units * pricePerUnit * couponRateBps / 10_000`, scaled by
    ///         `firstPeriodSecs / couponIntervalSecs` for period 1 (rounded down once). This is
    ///         the amount {payCoupon} pays for a snapshot balance of `units`.
    function couponAmount(uint256 period, uint256 units) public view returns (uint256) {
        uint256 periodSecs = period == 1 ? firstPeriodSecs : couponIntervalSecs;
        return Math.mulDiv(units * pricePerUnit * couponRateBps, periodSecs, 10_000 * couponIntervalSecs);
    }

    /// @dev Settles one (period, holder) from its record-date `balance`: pays the part that
    ///      is not frozen and escrows the frozen units' share. `paid + held == total`.
    function _settleCoupon(uint256 period, address holder, uint256 balance) private {
        uint256 total = couponAmount(period, balance);
        if (total == 0) {
            return;
        }
        uint256 frozenUnits = _frozenUnits(holder, balance);
        uint256 held = frozenUnits == 0 ? 0 : (frozenUnits == balance ? total : couponAmount(period, frozenUnits));
        uint256 paid = total - held;

        if (paid != 0) {
            couponPaid[period][holder] = true;
            // `from` is the immutable treasury, which pre-approves this desk ("Payment leg" above).
            // slither-disable-next-line arbitrary-send-erc20
            paymentToken.safeTransferFrom(treasury, holder, paid);
            emit CouponPaid(period, holder, paid);
        }
        if (held != 0) {
            withheld[period][holder] = held;
            totalWithheld += held;
            // `from` is the immutable treasury, which pre-approves this desk ("Payment leg" above).
            // slither-disable-next-line arbitrary-send-erc20
            paymentToken.safeTransferFrom(treasury, address(this), held);
            emit CouponWithheld(period, holder, held);
        }
    }

    /// @notice Pay out a matured holder's principal from the treasury and burn their
    ///         full position — payment and delivery of the final leg stay atomic. Gated
    ///         by the AML topic rather than KYC: redemption is a payout event, so the
    ///         desk re-checks the stricter of the two claim topics independently of
    ///         whichever topic gated the issuance.
    ///
    ///         Requires the final coupon period to be open (its record-date snapshot taken
    ///         by {payCoupon}), so burning the position cannot forfeit the last coupon.
    ///         Reverts {HolderFrozen} for a frozen holder — see {forceRedeem}.
    function redeem(address holder)
        external
        requiresOrgPermission(operatorOrg, REDEEM)
        requiresClaim(TOPIC_AML)
        whenNotPaused
    {
        _requireRedeemable(holder);
        if (_isFrozen(holder)) revert HolderFrozen(holder);

        uint256 balance = bond.balanceOf(holder);
        redeemed[holder] = true;
        uint256 principal = balance * pricePerUnit;
        if (balance > 0) {
            // `from` is the immutable treasury, which pre-approves this desk ("Payment leg" above).
            // slither-disable-next-line arbitrary-send-erc20
            paymentToken.safeTransferFrom(treasury, holder, principal);
            bond.burn(holder, balance);
        }
        emit BondRedeemed(holder, balance, principal);
    }

    // ── Legal-order releases for frozen holders ─────────────────────────────

    /// @notice Pays a holder's withheld coupon out of the desk's escrow once nothing of the
    ///         holder is frozen any more. Permissionless on purpose: the cash can only go to
    ///         `holder`, so the holder itself, the operator's payout bot or anyone else may
    ///         trigger it — and a holder whose hold has not lifted ({HolderFrozen}) is still
    ///         protected, because the escrow stays put. Reverts {NothingWithheld} when there is
    ///         nothing (left) to claim for that (period, holder). Stops while the desk is paused.
    function claimWithheldCoupon(uint256 period, address holder) external whenNotPaused returns (uint256 amount) {
        amount = withheld[period][holder];
        if (amount == 0) revert NothingWithheld(period, holder);
        if (_isFrozen(holder)) revert HolderFrozen(holder);

        withheld[period][holder] = 0;
        totalWithheld -= amount;
        couponPaid[period][holder] = true;
        paymentToken.safeTransfer(holder, amount);
        emit WithheldCouponClaimed(period, holder, amount);
    }

    /// @notice Pays a coupon withheld from a frozen holder out of the desk's escrow to the
    ///         destination a legal order names (`to` may be the holder itself once the order
    ///         lifts the hold). The `legalBasis` reference is emitted for the audit trail.
    function releaseWithheld(uint256 period, address holder, address to, string calldata legalBasis)
        external
        requiresOrgPermission(operatorOrg, LEGAL_ORDER)
        whenNotPaused
    {
        if (to == address(0)) revert ZeroAddress();
        if (bytes(legalBasis).length == 0) revert LegalBasisRequired();
        uint256 amount = withheld[period][holder];
        if (amount == 0) revert NothingWithheld(period, holder);

        withheld[period][holder] = 0;
        totalWithheld -= amount;
        couponPaid[period][holder] = true;
        paymentToken.safeTransfer(to, amount);
        emit WithheldReleased(period, holder, to, amount, legalBasis);
    }

    /// @notice Redeems a frozen holder's matured position under a legal order: burns the
    ///         full balance (T-REX `burn` also consumes frozen units) and pays the principal
    ///         to the destination the order names. Only for a frozen holder — an unfrozen
    ///         one goes through {redeem}.
    function forceRedeem(address holder, address to, string calldata legalBasis)
        external
        requiresOrgPermission(operatorOrg, LEGAL_ORDER)
        whenNotPaused
    {
        if (to == address(0)) revert ZeroAddress();
        if (bytes(legalBasis).length == 0) revert LegalBasisRequired();
        _requireRedeemable(holder);
        if (!_isFrozen(holder)) revert HolderNotFrozen(holder);

        uint256 balance = bond.balanceOf(holder);
        redeemed[holder] = true;
        uint256 principal = balance * pricePerUnit;
        if (balance > 0) {
            // `from` is the immutable treasury, which pre-approves this desk ("Payment leg" above).
            // slither-disable-next-line arbitrary-send-erc20
            paymentToken.safeTransferFrom(treasury, to, principal);
            bond.burn(holder, balance);
        }
        emit ForcedRedemption(holder, to, balance, principal, legalBasis);
    }

    function _requireRedeemable(address holder) private view {
        if (block.timestamp < maturityTimestamp) revert BondNotMatured(maturityTimestamp);
        if (redeemed[holder]) revert AlreadyRedeemed(holder);
        if (!periodOpened[finalCouponPeriod]) revert FinalCouponNotOpened(finalCouponPeriod);
    }

    /// @dev Frozen for payout purposes: the whole address is frozen, or part of its units.
    function _isFrozen(address holder) private view returns (bool) {
        return bond.isFrozen(holder) || bond.getFrozenTokens(holder) != 0;
    }

    /// @dev Units of `balance` that are frozen: all of them for a frozen address, otherwise
    ///      `min(frozen units, balance)`.
    function _frozenUnits(address holder, uint256 balance) private view returns (uint256) {
        if (bond.isFrozen(holder)) return balance;
        uint256 frozen = bond.getFrozenTokens(holder);
        return frozen < balance ? frozen : balance;
    }
}
