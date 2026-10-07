// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "./EwpgERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

/// @title EwpgERC7540
/// @notice Async tokenized vault (EIP-7540) for eWpG-regulated funds with T+1 / NAV-cutoff settlement.
///
/// @dev Extends EwpgERC4626 with an asynchronous request/claim flow:
///   1. Investor calls requestDeposit / requestRedeem.
///   2. Operator reviews pending requests, strikes NAV, then calls fulfillDepositRequest /
///      fulfillRedeemRequest to settle at the NAV effective at fulfillment time.
///   3. Settlement is pushed: fulfilment mints shares to / pays assets to the request owner
///      in the same transaction. There is no separate claim step.
///
/// @dev Settlement model: NAV is locked at *fulfillment time* (not request time).
///      Operators must call setNavPerShare before fulfilling requests to ensure a current strike.
///
/// @dev Forward pricing (dealing cut-off): once {setDealingCutoff} has been called, every request
///      records its *dealing point* — the first cut-off boundary strictly after the request. It may
///      only be fulfilled at a NAV that was struck at or after that point ({navStruckAt}), so a
///      request placed after the cut-off can never deal at a price that was already known (late
///      trading). Dealing points are fixed when the request is placed; changing the cut-off later
///      does not move them. While the cut-off has never been configured the vault behaves as
///      before (requests carry no dealing point): the operator must configure it before offering
///      the vault to investors.
///
/// @dev Pending subscriptions are escrow, not fund assets: `_pendingDepositAssets`
///      is excluded from totalAssets() and cannot fund a redemption payout, so a
///      redemption can never be paid out of another investor's unsettled deposit.
///
/// @dev Compliance holds are freeze-in-place: a frozen owner cannot be paid, and a
///      frozen payer cannot be refunded; the escrow stays in the vault until the
///      address is unfrozen or the registry force-cancels the request to a
///      destination named per case (forceCancelDepositRequest / forceCancelRedeemRequest).
///      Forced operations on the vault's own share custody are rejected.
contract EwpgERC7540 is EwpgERC4626 {
    using SafeERC20 for IERC20;

    // ── Storage ───────────────────────────────────────────────────────────────

    struct DepositRequest {
        uint256 assets;
        address controller;
        address owner;
        bool pending;
    }

    struct RedeemRequest {
        uint256 shares;
        address controller;
        address owner;
        bool pending;
    }

    mapping(uint256 => DepositRequest) private _depositRequests;
    mapping(uint256 => RedeemRequest) private _redeemRequests;
    uint256 private _requestCounter;
    uint256 private _minSettlementDelay; // seconds between request and earliest fulfillment

    mapping(uint256 => uint256) private _requestTimestamps;

    /// @dev Sum of the assets of all pending deposit requests (escrow, not AUM).
    uint256 private _pendingDepositAssets;
    /// @dev Who funded each deposit request; refunds go back here. Kept outside
    ///      DepositRequest so the depositRequest() view ABI stays unchanged.
    mapping(uint256 => address) private _depositPayer;

    /// @dev Dealing cut-off. Boundaries are `cutoff + k * period` seconds since the Unix epoch
    ///      (with the default 1-day period: the cut-off time of day, UTC).
    bool private _dealingCutoffConfigured;
    uint256 private _dealingCutoffSecondsOfDay;
    uint256 private _dealingPeriodSecs = 1 days;
    /// @dev Dealing point of each request (shared id space of deposit and redeem requests);
    ///      0 for requests placed before the cut-off was configured.
    mapping(uint256 => uint256) private _dealingPoints;

    // ── Events ────────────────────────────────────────────────────────────────

    event DepositRequested(
        uint256 indexed requestId,
        address indexed controller,
        address indexed owner,
        uint256 assets
    );
    event RedeemRequested(
        uint256 indexed requestId,
        address indexed controller,
        address indexed owner,
        uint256 shares
    );
    event DepositRequestFulfilled(
        uint256 indexed requestId,
        uint256 assets,
        uint256 shares,
        uint256 navAtFulfill
    );
    event RedeemRequestFulfilled(
        uint256 indexed requestId,
        uint256 shares,
        uint256 assets,
        uint256 navAtFulfill
    );
    event RequestCancelled(uint256 indexed requestId, address indexed by);
    event MinSettlementDelayUpdated(uint256 oldDelay, uint256 newDelay);
    event DealingCutoffUpdated(
        uint256 oldCutoffSecondsOfDay,
        uint256 oldPeriodSecs,
        uint256 newCutoffSecondsOfDay,
        uint256 newPeriodSecs
    );
    /// @notice Registry cancelled a pending request and moved its escrow (underlying for a
    ///         deposit request, shares for a redeem request) to `to` on the stated legal basis.
    event ForcedRequestCancelled(uint256 indexed requestId, address indexed to, string legalBasis);

    error AsyncOnly();
    /// @dev The NAV on the books was struck before the request's dealing point (forward pricing).
    error NavNotStruckAfterDealingPoint(uint256 requestId, uint256 dealingPoint, uint256 navStruckAt);

    // ── Constructor ───────────────────────────────────────────────────────────

    constructor(
        IERC20 underlying,
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    ) EwpgERC4626(underlying, name, symbol, registryWallet, _assetId) {}

    // ── Async-only ERC-4626 surface ───────────────────────────────────────────

    /// @dev Assets and shares may move only through the request lifecycle below.
    ///      Leaving the inherited synchronous entry points enabled would bypass
    ///      operator review and the configured settlement delay entirely.
    function deposit(uint256, address) public pure override returns (uint256) {
        revert AsyncOnly();
    }

    function mint(uint256, address) public pure override returns (uint256) {
        revert AsyncOnly();
    }

    function withdraw(uint256, address, address) public pure override returns (uint256) {
        revert AsyncOnly();
    }

    function redeem(uint256, address, address) public pure override returns (uint256) {
        revert AsyncOnly();
    }

    function maxDeposit(address) public pure override returns (uint256) {
        return 0;
    }

    function maxMint(address) public pure override returns (uint256) {
        return 0;
    }

    function maxWithdraw(address) public pure override returns (uint256) {
        return 0;
    }

    function maxRedeem(address) public pure override returns (uint256) {
        return 0;
    }

    // ── Accounting ────────────────────────────────────────────────────────────

    /// @notice Settled fund assets: the vault's underlying balance minus pending
    ///         (not yet fulfilled or cancelled) deposit escrow.
    function totalAssets() public view override returns (uint256) {
        uint256 balance = IERC20(asset()).balanceOf(address(this));
        return balance > _pendingDepositAssets ? balance - _pendingDepositAssets : 0;
    }

    function pendingDepositAssets() external view returns (uint256) {
        return _pendingDepositAssets;
    }

    /// @notice Headroom under {depositCap} for new deposit requests: the cap less settled assets
    ///         and the escrow of requests still pending. `type(uint256).max` when no cap is set.
    ///         ({maxDeposit} stays 0: the synchronous ERC-4626 path is disabled.)
    function maxRequestDeposit(address) public view returns (uint256) {
        uint256 cap = depositCap();
        if (cap == 0) return type(uint256).max;
        uint256 committed = totalAssets() + _pendingDepositAssets;
        return committed >= cap ? 0 : cap - committed;
    }

    // ── Settlement delay ──────────────────────────────────────────────────────

    function setMinSettlementDelay(uint256 delaySecs) external onlyRegistry {
        emit MinSettlementDelayUpdated(_minSettlementDelay, delaySecs);
        _minSettlementDelay = delaySecs;
    }

    function minSettlementDelay() external view returns (uint256) {
        return _minSettlementDelay;
    }

    // ── Dealing cut-off (forward pricing) ─────────────────────────────────────

    /// @notice Configures the dealing cut-off: `cutoffSecondsOfDay` (0..86399, seconds after
    ///         00:00 UTC) and the dealing period (default 1 day; boundaries are
    ///         `cutoff + k * periodSecs` since the epoch). Applies to requests placed afterwards;
    ///         existing requests keep their dealing point. The first call switches forward
    ///         pricing on for the vault.
    function setDealingCutoff(uint256 cutoffSecondsOfDay, uint256 periodSecs) external onlyRegistry {
        require(cutoffSecondsOfDay < 1 days, "EwpgERC7540: cut-off out of range");
        require(periodSecs > 0, "EwpgERC7540: zero dealing period");
        emit DealingCutoffUpdated(_dealingCutoffSecondsOfDay, _dealingPeriodSecs, cutoffSecondsOfDay, periodSecs);
        _dealingCutoffConfigured = true;
        _dealingCutoffSecondsOfDay = cutoffSecondsOfDay;
        _dealingPeriodSecs = periodSecs;
    }

    function dealingCutoffConfigured() external view returns (bool) {
        return _dealingCutoffConfigured;
    }

    function dealingCutoffSecondsOfDay() external view returns (uint256) {
        return _dealingCutoffSecondsOfDay;
    }

    function dealingPeriodSecs() external view returns (uint256) {
        return _dealingPeriodSecs;
    }

    /// @notice The dealing point a request placed in this block would get: the first cut-off
    ///         boundary strictly after `block.timestamp`. 0 while the cut-off is not configured.
    function nextDealingPoint() public view returns (uint256) {
        if (!_dealingCutoffConfigured) return 0;
        uint256 cutoff = _dealingCutoffSecondsOfDay;
        uint256 period = _dealingPeriodSecs;
        uint256 ts = block.timestamp;
        return ts < cutoff ? cutoff : cutoff + ((ts - cutoff) / period + 1) * period;
    }

    /// @notice The dealing point recorded for a deposit or redeem request; 0 if the request was
    ///         placed before the cut-off was configured (it then settles as before).
    function dealingPointOf(uint256 requestId) external view returns (uint256) {
        return _dealingPoints[requestId];
    }

    /// @dev Forward pricing: the NAV used for settlement must have been struck at or after the
    ///      request's dealing point. Trivially satisfied for requests without a dealing point.
    function _requireNavStruckAfterDealingPoint(uint256 requestId) private view {
        uint256 dealingPoint = _dealingPoints[requestId];
        uint256 struckAt = navStruckAt();
        if (struckAt < dealingPoint) revert NavNotStruckAfterDealingPoint(requestId, dealingPoint, struckAt);
    }

    // ── Deposit request ───────────────────────────────────────────────────────

    /// @notice Investor places a deposit request. Assets are transferred in immediately and held
    ///         pending settlement. The request is booked at the amount the vault actually
    ///         *received* (balance delta), so a fee-on-transfer underlying cannot mint shares for
    ///         cash that never arrived, and it must fit under {depositCap} together with settled
    ///         assets and the other pending requests.
    function requestDeposit(uint256 assets, address controller, address owner)
        external
        returns (uint256 requestId)
    {
        require(assets > 0, "EwpgERC7540: zero assets");
        require(isWhitelisted(owner), "EwpgERC7540: owner not whitelisted");
        require(!isFrozen(msg.sender), "EwpgERC7540: payer is frozen");
        require(!isFrozen(owner), "EwpgERC7540: owner is frozen");
        require(assets <= maxRequestDeposit(owner), "EwpgERC7540: deposit cap exceeded");
        // SafeERC20: tokens that signal failure by returning false (instead of
        // reverting) would otherwise leave the request recorded WITHOUT the
        // assets ever arriving — free shares at fulfillment.
        IERC20 underlying = IERC20(asset());
        uint256 balanceBefore = underlying.balanceOf(address(this));
        underlying.safeTransferFrom(msg.sender, address(this), assets);
        uint256 received = underlying.balanceOf(address(this)) - balanceBefore;
        require(received > 0, "EwpgERC7540: nothing received");
        requestId = ++_requestCounter;
        _depositRequests[requestId] = DepositRequest({ assets: received, controller: controller, owner: owner, pending: true });
        _depositPayer[requestId] = msg.sender;
        _pendingDepositAssets += received;
        _requestTimestamps[requestId] = block.timestamp;
        _dealingPoints[requestId] = nextDealingPoint();
        emit DepositRequested(requestId, controller, owner, received);
    }

    /// @notice Operator fulfills a pending deposit request at the current NAV, which must have been
    ///         struck at or after the request's dealing point (see {setDealingCutoff}).
    function fulfillDepositRequest(uint256 requestId) external onlyRegistry {
        DepositRequest storage req = _depositRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(block.timestamp >= _requestTimestamps[requestId] + _minSettlementDelay,
                "EwpgERC7540: settlement delay not elapsed");
        // A regulated fund must never settle at the implicit 1:1 pre-strike rate.
        require(currentNavPerShare() > 0, "EwpgERC7540: NAV not struck");
        _requireNavStruckAfterDealingPoint(requestId);
        req.pending = false;
        _pendingDepositAssets -= req.assets;
        uint256 shares = convertToShares(req.assets);
        _mint(req.owner, shares);
        emit DepositRequestFulfilled(requestId, req.assets, shares, currentNavPerShare());
    }

    // ── Redeem request ────────────────────────────────────────────────────────

    /// @notice Investor places a redemption request. Shares are locked pending settlement.
    /// @dev Authorization per EIP-7540: the caller must be the share owner or
    ///      hold a sufficient ERC-20 allowance from the owner. Without this
    ///      check, anyone could lock an arbitrary holder's shares and force
    ///      their divestment at the next NAV strike.
    function requestRedeem(uint256 shares, address controller, address owner)
        external
        returns (uint256 requestId)
    {
        require(shares > 0, "EwpgERC7540: zero shares");
        require(balanceOf(owner) >= shares, "EwpgERC7540: insufficient shares");
        if (msg.sender != owner) {
            // The share legs below only see owner and vault: an allowance holder frozen after it
            // was approved must not be able to act on the owner's position (an allowance granted
            // before the freeze stays on the books).
            require(!isFrozen(msg.sender), "EwpgERC7540: caller is frozen");
            _spendAllowance(owner, msg.sender, shares);
        }
        _transfer(owner, address(this), shares);
        requestId = ++_requestCounter;
        _redeemRequests[requestId] = RedeemRequest({ shares: shares, controller: controller, owner: owner, pending: true });
        _requestTimestamps[requestId] = block.timestamp;
        _dealingPoints[requestId] = nextDealingPoint();
        emit RedeemRequested(requestId, controller, owner, shares);
    }

    /// @notice Operator fulfills a pending redemption request at the current NAV, which must have been
    ///         struck at or after the request's dealing point (see {setDealingCutoff}).
    function fulfillRedeemRequest(uint256 requestId) external onlyRegistry {
        RedeemRequest storage req = _redeemRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(block.timestamp >= _requestTimestamps[requestId] + _minSettlementDelay,
                "EwpgERC7540: settlement delay not elapsed");
        require(currentNavPerShare() > 0, "EwpgERC7540: NAV not struck");
        _requireNavStruckAfterDealingPoint(requestId);
        // The payout leg is invisible to the share hook (_burn is from the
        // vault), so the owner's freeze must be checked explicitly.
        require(!isFrozen(req.owner), "EwpgERC7540: owner is frozen");
        req.pending = false;
        uint256 assets = convertToAssets(req.shares);
        require(assets <= totalAssets(), "EwpgERC7540: insufficient settled liquidity");
        _burn(address(this), req.shares);
        IERC20(asset()).safeTransfer(req.owner, assets);
        emit RedeemRequestFulfilled(requestId, req.shares, assets, currentNavPerShare());
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    function cancelDepositRequest(uint256 requestId) external {
        DepositRequest storage req = _depositRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(msg.sender == req.controller || msg.sender == registry,
                "EwpgERC7540: not authorised to cancel");
        // Refund goes to whoever funded the request — refunding the owner would
        // let a payer relay cash to a third party via request→cancel. A frozen
        // payer's money stays escrowed (freeze-in-place).
        address payer = _depositPayer[requestId];
        require(!isFrozen(payer), "EwpgERC7540: refund recipient is frozen");
        req.pending = false;
        _pendingDepositAssets -= req.assets;
        IERC20(asset()).safeTransfer(payer, req.assets);
        emit RequestCancelled(requestId, msg.sender);
    }

    function cancelRedeemRequest(uint256 requestId) external {
        RedeemRequest storage req = _redeemRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(msg.sender == req.controller || msg.sender == registry,
                "EwpgERC7540: not authorised to cancel");
        req.pending = false;
        _transfer(address(this), req.owner, req.shares);
        emit RequestCancelled(requestId, msg.sender);
    }

    // ── Forced cancel (compliance hold release on a legal basis) ─────────────
    //
    // Policy-neutral: the destination is supplied per case by the registry.
    // These are the only way to move a pending request's escrow while its
    // owner / payer is frozen; forcedTransfer / forceBurn on vault custody are
    // rejected because they would silently strand other investors' requests.

    function forceCancelDepositRequest(uint256 requestId, address to, string calldata legalBasis)
        external onlyRegistry
    {
        DepositRequest storage req = _depositRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        _requireForceDestination(to);
        req.pending = false;
        _pendingDepositAssets -= req.assets;
        IERC20(asset()).safeTransfer(to, req.assets);
        emit ForcedRequestCancelled(requestId, to, legalBasis);
    }

    function forceCancelRedeemRequest(uint256 requestId, address to, string calldata legalBasis)
        external onlyRegistry
    {
        RedeemRequest storage req = _redeemRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        _requireForceDestination(to);
        req.pending = false;
        _inForceOp = true;
        _transfer(address(this), to, req.shares);
        _inForceOp = false;
        emit ForcedRequestCancelled(requestId, to, legalBasis);
    }

    function _requireForceDestination(address to) private view {
        require(to != address(0) && to != address(this), "EwpgERC7540: invalid destination");
    }

    /// @dev Vault self-custody holds every pending redeemer's escrowed shares;
    ///      a forced operation on it would break other requests' funding.
    function _beforeForceOp(address account) internal view override {
        require(account != address(this), "EwpgERC7540: vault custody is not force-movable");
    }

    // ── View ──────────────────────────────────────────────────────────────────

    function depositRequest(uint256 requestId)
        external view
        returns (uint256 assets, address controller, address owner, bool pending)
    {
        DepositRequest storage req = _depositRequests[requestId];
        return (req.assets, req.controller, req.owner, req.pending);
    }

    function depositRequestPayer(uint256 requestId) external view returns (address) {
        return _depositPayer[requestId];
    }

    function redeemRequest(uint256 requestId)
        external view
        returns (uint256 shares, address controller, address owner, bool pending)
    {
        RedeemRequest storage req = _redeemRequests[requestId];
        return (req.shares, req.controller, req.owner, req.pending);
    }
}
