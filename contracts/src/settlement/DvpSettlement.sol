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
///
///      Trade ids are derived, never chosen: `tradeId = keccak256(abi.encode(chainid, this,
///      locker, clientRef))` (see {tradeIdFor}), so no third party can occupy the id of a
///      trade another party is about to lock. The counterparty binds its {settle} call to
///      the terms it agreed off-chain by passing their {hashTerms} digest; a trade whose
///      stored terms differ reverts {TermsMismatch} instead of moving the counterparty's
///      approved funds.
///
///      Compliance holds are freeze-in-place: {settle} reverts {PartyFrozen} while the
///      asset token (if it answers `isFrozen(address)`, as T-REX does) reports the seller
///      or buyer frozen. The escrow then stays here until the freeze is lifted, the trade
///      is cancelled, or it is released under a legal order. A legal-order release is never a
///      one-key redirect: {forceCancel} (operator) can only return the escrow to one of the
///      trade's own parties — e.g. to the counterparty when the locker is frozen — and a release to
///      any other destination named in an order goes through the separate `LEGAL_ORDER_ROLE`
///      ({proposeForceCancel} → {LEGAL_ORDER_DELAY} → {executeForceCancel}), during which the
///      parties and monitors can see it coming and the admin can {withdrawForceCancel} it.
contract DvpSettlement is ReentrancyGuard, AccessControl {
    using SafeERC20 for IERC20;

    /// @notice Role held by the registry operator's backend wallet(s).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    /// @notice Role that may propose releasing an escrow to a destination that is not one of the
    ///         trade's parties (a third-party custodian or authority named in a legal order).
    ///         Not granted at construction and deliberately separate from {OPERATOR_ROLE}: hold it
    ///         on a different key/multisig than the backend's operator wallet.
    bytes32 public constant LEGAL_ORDER_ROLE = keccak256("LEGAL_ORDER_ROLE");

    /// @notice Delay between {proposeForceCancel} and the earliest {executeForceCancel}.
    uint256 public constant LEGAL_ORDER_DELAY = 2 days;

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

    /// @dev A proposed release of a trade's escrow to a destination outside the trade (see
    ///      {proposeForceCancel}). `executableAt == 0`: none proposed.
    struct PendingForceCancel {
        address to;
        uint64 executableAt;
        string legalBasis;
    }

    mapping(bytes32 => PendingForceCancel) public pendingForceCancels;

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
    /// @notice The escrowed leg was released by the operator to a destination named in a
    ///         legal order, instead of to the locker.
    event TradeForceCancelled(bytes32 indexed tradeId, address indexed to, string legalBasis);
    /// @notice A release of the escrow to `to`, a destination outside the trade, was proposed under
    ///         a legal order; it can be executed from `executableAt` unless withdrawn or mooted by
    ///         the trade being settled or cancelled first.
    event ForceCancelProposed(bytes32 indexed tradeId, address indexed to, uint64 executableAt, string legalBasis);
    event ForceCancelWithdrawn(bytes32 indexed tradeId, address indexed by);

    error TradeAlreadyExists(bytes32 tradeId);
    error TradeNotLocked(bytes32 tradeId);
    error TradeExpired(bytes32 tradeId, uint64 expiry);
    error TradeNotExpired(bytes32 tradeId, uint64 expiry);
    error NotCounterparty(bytes32 tradeId, address caller);
    error NotTradeParty(bytes32 tradeId, address caller);
    error InvalidTrade();
    error TermsMismatch(bytes32 tradeId, bytes32 expectedTermsHash, bytes32 actualTermsHash);
    error PartyFrozen(bytes32 tradeId, address party);
    error InvalidDestination();
    /// @notice {forceCancel} may only return the escrow to the trade's locker or counterparty;
    ///         use {proposeForceCancel} for a destination named in a legal order.
    error DestinationNotTradeParty(bytes32 tradeId, address to);
    error ForceCancelNotProposed(bytes32 tradeId);
    error ForceCancelNotReady(bytes32 tradeId, uint64 executableAt);
    error ForceCancelAlreadyProposed(bytes32 tradeId);

    /// @notice Seller escrows the asset leg. The buyer completes via {settle} by paying
    ///         the payment leg, before `expiry`.
    /// @param clientRef The locker's own reference for this trade (e.g. an RFQ id); the
    ///        trade id is derived from it and the caller, see {tradeIdFor}.
    /// @return tradeId The derived trade id both parties use from here on.
    function lockAsset(
        bytes32 clientRef,
        address buyer,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        uint64 expiry
    ) external nonReentrant whenNotPaused returns (bytes32 tradeId) {
        tradeId = tradeIdFor(msg.sender, clientRef);
        _initTrade(
            tradeId, msg.sender, buyer, assetToken, assetAmount, paymentToken, paymentAmount, LockedLeg.Asset, expiry
        );
        assetToken.safeTransferFrom(msg.sender, address(this), assetAmount);
        _emitLocked(tradeId);
    }

    /// @notice Buyer escrows the payment leg. The seller completes via {settle} by
    ///         delivering the asset leg, before `expiry`.
    /// @param clientRef The locker's own reference for this trade; see {lockAsset}.
    /// @return tradeId The derived trade id both parties use from here on.
    function lockPayment(
        bytes32 clientRef,
        address seller,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        uint64 expiry
    ) external nonReentrant whenNotPaused returns (bytes32 tradeId) {
        tradeId = tradeIdFor(msg.sender, clientRef);
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
    /// @param expectedTermsHash {hashTerms} over the terms the counterparty agreed to,
    ///        computed from its own record of the deal — not read back from {termsHashOf},
    ///        which would only echo whatever the locker stored.
    function settle(bytes32 tradeId, bytes32 expectedTermsHash) external nonReentrant whenNotPaused {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);
        if (block.timestamp >= trade.expiry) revert TradeExpired(tradeId, trade.expiry);
        bytes32 actualTermsHash = _termsHash(trade);
        if (actualTermsHash != expectedTermsHash) {
            revert TermsMismatch(tradeId, expectedTermsHash, actualTermsHash);
        }
        if (_isFrozen(trade.assetToken, trade.seller)) revert PartyFrozen(tradeId, trade.seller);
        if (_isFrozen(trade.assetToken, trade.buyer)) revert PartyFrozen(tradeId, trade.buyer);

        trade.state = TradeState.Settled;
        if (trade.lockedLeg == LockedLeg.Asset) {
            if (msg.sender != trade.buyer) revert NotCounterparty(tradeId, msg.sender);
            // `from` is the caller: the check above reverts unless msg.sender == trade.buyer.
            // slither-disable-next-line arbitrary-send-erc20
            trade.paymentToken.safeTransferFrom(trade.buyer, trade.seller, trade.paymentAmount);
            trade.assetToken.safeTransfer(trade.buyer, trade.assetAmount);
        } else {
            if (msg.sender != trade.seller) revert NotCounterparty(tradeId, msg.sender);
            // `from` is the caller: the check above reverts unless msg.sender == trade.seller.
            // slither-disable-next-line arbitrary-send-erc20
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

    /// @notice Legal-order release to one of the trade's own parties: cancels a locked trade and
    ///         sends the escrowed leg to `to`, which must be the trade's locker or its counterparty
    ///         (e.g. to the counterparty when the locker is frozen and a return to it would revert
    ///         at the token). The operator key therefore can never move an escrow out of the trade;
    ///         a destination outside it needs {proposeForceCancel}. Like {cancel}, available while
    ///         paused.
    /// @param legalBasis Reference to the legal authority (e.g. "BaFin Az. 2026-001").
    function forceCancel(bytes32 tradeId, address to, string calldata legalBasis)
        external
        nonReentrant
        onlyRole(OPERATOR_ROLE)
    {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);
        if (to == address(0) || to == address(this)) revert InvalidDestination();
        if (to != trade.seller && to != trade.buyer) revert DestinationNotTradeParty(tradeId, to);

        _forceCancel(tradeId, trade, to, legalBasis);
    }

    /// @notice Proposes releasing the escrow to `to` — a destination outside the trade (a
    ///         custodian or authority named in a legal order). Executable by
    ///         {executeForceCancel} after {LEGAL_ORDER_DELAY}; the trade stays settleable and
    ///         cancellable meanwhile, and either of those moots the proposal. Replacing a pending
    ///         proposal requires {withdrawForceCancel} first, so a destination cannot be swapped
    ///         without the delay restarting.
    function proposeForceCancel(bytes32 tradeId, address to, string calldata legalBasis)
        external
        onlyRole(LEGAL_ORDER_ROLE)
    {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);
        if (to == address(0) || to == address(this)) revert InvalidDestination();
        PendingForceCancel storage pending = pendingForceCancels[tradeId];
        if (pending.executableAt != 0) revert ForceCancelAlreadyProposed(tradeId);

        uint64 executableAt = uint64(block.timestamp + LEGAL_ORDER_DELAY);
        pendingForceCancels[tradeId] = PendingForceCancel({to: to, executableAt: executableAt, legalBasis: legalBasis});
        emit ForceCancelProposed(tradeId, to, executableAt, legalBasis);
    }

    /// @notice Executes a matured {proposeForceCancel}: cancels the still-locked trade and sends
    ///         the escrowed leg to the proposed destination. Emits {TradeForceCancelled} with the
    ///         legal basis recorded at proposal. Like {cancel}, available while paused.
    function executeForceCancel(bytes32 tradeId) external nonReentrant onlyRole(LEGAL_ORDER_ROLE) {
        Trade storage trade = trades[tradeId];
        if (trade.state != TradeState.Locked) revert TradeNotLocked(tradeId);
        PendingForceCancel memory pending = pendingForceCancels[tradeId];
        if (pending.executableAt == 0) revert ForceCancelNotProposed(tradeId);
        if (block.timestamp < pending.executableAt) revert ForceCancelNotReady(tradeId, pending.executableAt);

        delete pendingForceCancels[tradeId];
        _forceCancel(tradeId, trade, pending.to, pending.legalBasis);
    }

    /// @notice Withdraws a pending {proposeForceCancel} (a superseded or mistaken order). The
    ///         proposer's role or the admin may do so.
    function withdrawForceCancel(bytes32 tradeId) external {
        if (!hasRole(LEGAL_ORDER_ROLE, msg.sender) && !hasRole(DEFAULT_ADMIN_ROLE, msg.sender)) {
            revert AccessControlUnauthorizedAccount(msg.sender, LEGAL_ORDER_ROLE);
        }
        if (pendingForceCancels[tradeId].executableAt == 0) revert ForceCancelNotProposed(tradeId);
        delete pendingForceCancels[tradeId];
        emit ForceCancelWithdrawn(tradeId, msg.sender);
    }

    function _forceCancel(bytes32 tradeId, Trade storage trade, address to, string memory legalBasis) private {
        (IERC20 lockedToken, uint256 lockedAmount) = trade.lockedLeg == LockedLeg.Asset
            ? (trade.assetToken, trade.assetAmount)
            : (trade.paymentToken, trade.paymentAmount);

        trade.state = TradeState.Cancelled;
        lockedToken.safeTransfer(to, lockedAmount);
        emit TradeForceCancelled(tradeId, to, legalBasis);
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    /// @notice The trade id a `locker` gets for its `clientRef` on this deployment and chain.
    ///         Namespacing by locker means an id can only ever be occupied by the party
    ///         that derives it.
    function tradeIdFor(address locker, bytes32 clientRef) public view returns (bytes32) {
        return keccak256(abi.encode(block.chainid, address(this), locker, clientRef));
    }

    /// @notice Digest of a trade's economic terms, as {settle} expects it. Counterparties
    ///         compute it from their own record of the agreed deal.
    function hashTerms(
        address seller,
        address buyer,
        IERC20 assetToken,
        uint256 assetAmount,
        IERC20 paymentToken,
        uint256 paymentAmount,
        LockedLeg lockedLeg,
        uint64 expiry
    ) public pure returns (bytes32) {
        return keccak256(
            abi.encode(seller, buyer, assetToken, assetAmount, paymentToken, paymentAmount, lockedLeg, expiry)
        );
    }

    /// @notice {hashTerms} over the terms stored for `tradeId`; zero for an unknown id.
    function termsHashOf(bytes32 tradeId) external view returns (bytes32) {
        Trade storage trade = trades[tradeId];
        if (trade.state == TradeState.None) return bytes32(0);
        return _termsHash(trade);
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

    function _termsHash(Trade storage trade) private view returns (bytes32) {
        return hashTerms(
            trade.seller,
            trade.buyer,
            trade.assetToken,
            trade.assetAmount,
            trade.paymentToken,
            trade.paymentAmount,
            trade.lockedLeg,
            trade.expiry
        );
    }

    /// @dev T-REX-style freeze probe. A token without `isFrozen(address)` (plain ERC-20),
    ///      a reverting call or short return data all count as "not frozen".
    function _isFrozen(IERC20 token, address account) private view returns (bool) {
        (bool ok, bytes memory ret) = address(token).staticcall(abi.encodeWithSignature("isFrozen(address)", account));
        return ok && ret.length >= 32 && abi.decode(ret, (uint256)) != 0;
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
