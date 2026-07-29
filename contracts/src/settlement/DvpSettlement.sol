// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "@openzeppelin/contracts/access/AccessControl.sol";

/// @title DvpSettlement
/// @notice ERC-7573-style delivery-versus-payment for a security-token leg against a
///         payment-token leg (e.g. a MiCAR e-money token such as AUEUR or USDC) on the
///         same chain. One party locks its leg in escrow; the counterparty then settles
///         both legs in one successful transaction, or the trade expires and the
///         escrowed leg is reclaimed. Either leg may be the locked one:
///         exact-leg conservation assumes payment and asset tokens without transfer fees,
///         rebases, or other balance-changing hooks. Chain finality and legal-register
///         reconciliation are separate from this transaction-level behavior.
///
///           - {lockAsset}:   the seller escrows the asset token; the buyer settles by
///                            paying the payment token (buyer → seller) and receives the
///                            escrowed asset.
///           - {lockPayment}: the buyer escrows the payment token; the seller settles by
///                            delivering the asset token (seller → buyer) and receives
///                            the escrowed payment.
///
///         This is the operator-provided "erc7573-dvp" payment rail dApps can declare in
///         their marketplace manifest instead of building their own settlement.
///
/// @dev ERC-7573 proper coordinates two *different* chains through encrypted keys; on a
///      a single chain this contract provides escrow plus same-transaction execution for
///      compatible exact-transfer tokens. It does not provide cross-chain finality or legal
///      settlement evidence.
///
///      ERC-3643 caveat: T-REX tokens refuse transfers to addresses without a verified
///      ONCHAINID, so escrowing such an asset requires this contract to be registered in
///      the token's identity registry first. When that is not desirable, lock the
///      payment leg instead ({lockPayment}) — plain ERC-20 stablecoins have no such
///      restriction, and the asset then moves directly seller → buyer on settlement,
///      passing T-REX compliance exactly as a normal transfer.
contract DvpSettlement is ReentrancyGuard, AccessControl {
    using SafeERC20 for IERC20;

    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    /// @notice Circuit breaker for new exposure ({lockAsset}, {lockPayment}, {settle}) —
    ///         e.g. when this rail's underlying payment token is disabled at the catalog
    ///         level. {cancel} is deliberately never gated by this: a party's escrowed
    ///         funds must always be recoverable, paused or not.
    bool public paused;

    event Paused(address indexed by);
    event Unpaused(address indexed by);

    error ContractPaused();

    constructor(address admin) {
        require(admin != address(0), "DvpSettlement: zero admin address");
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, admin);
    }

    modifier whenNotPaused() {
        if (paused) revert ContractPaused();
        _;
    }

    /// @notice Suspends new trade locks and settlement. Existing trades may still be
    ///         {cancel}led by their locker (post-expiry) or counterparty (any time).
    function pause() external onlyRole(OPERATOR_ROLE) {
        paused = true;
        emit Paused(msg.sender);
    }

    /// @notice Resumes normal operation.
    function unpause() external onlyRole(OPERATOR_ROLE) {
        paused = false;
        emit Unpaused(msg.sender);
    }

    enum TradeState {
        None,
        Locked,
        Settled,
        Cancelled
    }

    /// @notice Which leg sits in escrow.
    enum LockedLeg {
        Asset,
        Payment
    }

    struct Trade {
        address seller; // delivers assetToken, receives paymentToken
        address buyer; // pays paymentToken, receives assetToken
        IERC20 assetToken;
        uint256 assetAmount;
        IERC20 paymentToken;
        uint256 paymentAmount;
        LockedLeg lockedLeg;
        uint64 expiry; // after this timestamp the locker may reclaim via cancel()
        TradeState state;
    }

    mapping(bytes32 => Trade) public trades;

    event LegLocked(
        bytes32 indexed tradeId,
        address indexed seller,
        address indexed buyer,
        LockedLeg lockedLeg,
        address assetToken,
        uint256 assetAmount,
        address paymentToken,
        uint256 paymentAmount,
        uint64 expiry
    );
    event TradeSettled(bytes32 indexed tradeId);
    event TradeCancelled(bytes32 indexed tradeId, address indexed by);

    error TradeAlreadyExists(bytes32 tradeId);
    error TradeNotLocked(bytes32 tradeId);
    error TradeExpired(bytes32 tradeId, uint64 expiry);
    error TradeNotExpired(bytes32 tradeId, uint64 expiry);
    error NotCounterparty(bytes32 tradeId, address caller);
    error NotTradeParty(bytes32 tradeId, address caller);
    error InvalidTrade();

    /// @notice Seller escrows the asset leg. The buyer completes via {settle} by paying
    ///         the payment leg, before `expiry`.
    function lockAsset(
        bytes32 tradeId,
        address buyer,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        uint64 expiry
    ) external nonReentrant whenNotPaused {
        _initTrade(
            tradeId, msg.sender, buyer, assetToken, assetAmount, paymentToken, paymentAmount, LockedLeg.Asset, expiry
        );
        assetToken.safeTransferFrom(msg.sender, address(this), assetAmount);
        _emitLocked(tradeId);
    }

    /// @notice Buyer escrows the payment leg. The seller completes via {settle} by
    ///         delivering the asset leg, before `expiry`.
    function lockPayment(
        bytes32 tradeId,
        address seller,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        uint64 expiry
    ) external nonReentrant whenNotPaused {
        _initTrade(
            tradeId, seller, msg.sender, assetToken, assetAmount, paymentToken, paymentAmount, LockedLeg.Payment, expiry
        );
        paymentToken.safeTransferFrom(msg.sender, address(this), paymentAmount);
        _emitLocked(tradeId);
    }

    /// @notice Executes both configured token calls in one successful transaction. This is only
    ///         technical same-transaction execution under the exact-transfer assumptions below,
    ///         not evidence of cross-system finality or legal effect. Only the counterparty of the escrowed leg
    ///         may call: it delivers its own leg (via prior ERC-20 approval to this
    ///         contract) and receives the escrowed one in the same transaction.
    function settle(bytes32 tradeId) external nonReentrant whenNotPaused {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);
        if (block.timestamp >= trade.expiry) revert TradeExpired(tradeId, trade.expiry);

        trade.state = TradeState.Settled;
        if (trade.lockedLeg == LockedLeg.Asset) {
            if (msg.sender != trade.buyer) revert NotCounterparty(tradeId, msg.sender);
            trade.paymentToken.safeTransferFrom(trade.buyer, trade.seller, trade.paymentAmount);
            trade.assetToken.safeTransfer(trade.buyer, trade.assetAmount);
        } else {
            if (msg.sender != trade.seller) revert NotCounterparty(tradeId, msg.sender);
            trade.assetToken.safeTransferFrom(trade.seller, trade.buyer, trade.assetAmount);
            trade.paymentToken.safeTransfer(trade.seller, trade.paymentAmount);
        }
        emit TradeSettled(tradeId);
    }

    /// @notice Returns the escrowed leg to the party that locked it. The counterparty
    ///         may cancel (renounce) at any time; the locker itself only once the trade
    ///         has expired — before expiry the counterparty's right to settle is firm.
    function cancel(bytes32 tradeId) external nonReentrant {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);

        (address locker, address counterparty, IERC20 lockedToken, uint256 lockedAmount) = trade.lockedLeg
            == LockedLeg.Asset
            ? (trade.seller, trade.buyer, trade.assetToken, trade.assetAmount)
            : (trade.buyer, trade.seller, trade.paymentToken, trade.paymentAmount);

        if (msg.sender == locker) {
            if (block.timestamp < trade.expiry) revert TradeNotExpired(tradeId, trade.expiry);
        } else if (msg.sender != counterparty) {
            revert NotTradeParty(tradeId, msg.sender);
        }

        trade.state = TradeState.Cancelled;
        lockedToken.safeTransfer(locker, lockedAmount);
        emit TradeCancelled(tradeId, msg.sender);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    function _initTrade(
        bytes32 tradeId,
        address seller,
        address buyer,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        LockedLeg lockedLeg,
        uint64 expiry
    ) private {
        if (trades[tradeId].state != TradeState.None) revert TradeAlreadyExists(tradeId);
        if (
            seller == address(0) || buyer == address(0) || seller == buyer || address(assetToken) == address(0)
                || address(paymentToken) == address(0) || assetAmount == 0 || paymentAmount == 0
                || expiry <= block.timestamp
        ) revert InvalidTrade();

        trades[tradeId] = Trade({
            seller: seller,
            buyer: buyer,
            assetToken: assetToken,
            assetAmount: assetAmount,
            paymentToken: paymentToken,
            paymentAmount: paymentAmount,
            lockedLeg: lockedLeg,
            expiry: expiry,
            state: TradeState.Locked
        });
    }

    function _emitLocked(bytes32 tradeId) private {
        Trade storage trade = trades[tradeId];
        emit LegLocked(
            tradeId,
            trade.seller,
            trade.buyer,
            trade.lockedLeg,
            address(trade.assetToken),
            trade.assetAmount,
            address(trade.paymentToken),
            trade.paymentAmount,
            trade.expiry
        );
    }
}
