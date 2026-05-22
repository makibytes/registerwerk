// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "./EwpgERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";

/// @title EwpgERC7540
/// @notice Async tokenized vault (EIP-7540) for eWpG-regulated funds with T+1 / NAV-cutoff settlement.
///
/// @dev Extends EwpgERC4626 with an asynchronous request/claim flow:
///   1. Investor calls requestDeposit / requestRedeem.
///   2. Operator reviews pending requests, strikes NAV, then calls fulfillDepositRequest /
///      fulfillRedeemRequest to settle at the NAV effective at fulfillment time.
///   3. Investor claims assets via claim (separate from ERC-4626 deposit/redeem).
///
/// @dev Settlement model: NAV is locked at *fulfillment time* (not request time).
///      Operators must call setNavPerShare before fulfilling requests to ensure a current strike.
contract EwpgERC7540 is EwpgERC4626 {
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

    // ── Constructor ───────────────────────────────────────────────────────────

    constructor(
        IERC20 underlying,
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    ) EwpgERC4626(underlying, name, symbol, registryWallet, _assetId) {}

    // ── Settlement delay ──────────────────────────────────────────────────────

    function setMinSettlementDelay(uint256 delaySecs) external onlyRegistry {
        emit MinSettlementDelayUpdated(_minSettlementDelay, delaySecs);
        _minSettlementDelay = delaySecs;
    }

    function minSettlementDelay() external view returns (uint256) {
        return _minSettlementDelay;
    }

    // ── Deposit request ───────────────────────────────────────────────────────

    /// @notice Investor places a deposit request. Assets are transferred in immediately and held pending settlement.
    function requestDeposit(uint256 assets, address controller, address owner)
        external
        returns (uint256 requestId)
    {
        require(assets > 0, "EwpgERC7540: zero assets");
        require(isWhitelisted(owner), "EwpgERC7540: owner not whitelisted");
        IERC20(asset()).transferFrom(msg.sender, address(this), assets);
        requestId = ++_requestCounter;
        _depositRequests[requestId] = DepositRequest({ assets: assets, controller: controller, owner: owner, pending: true });
        _requestTimestamps[requestId] = block.timestamp;
        emit DepositRequested(requestId, controller, owner, assets);
    }

    /// @notice Operator fulfills a pending deposit request at current NAV.
    function fulfillDepositRequest(uint256 requestId) external onlyRegistry {
        DepositRequest storage req = _depositRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(block.timestamp >= _requestTimestamps[requestId] + _minSettlementDelay,
                "EwpgERC7540: settlement delay not elapsed");
        req.pending = false;
        uint256 shares = convertToShares(req.assets);
        _mint(req.owner, shares);
        emit DepositRequestFulfilled(requestId, req.assets, shares, currentNavPerShare());
    }

    // ── Redeem request ────────────────────────────────────────────────────────

    /// @notice Investor places a redemption request. Shares are locked pending settlement.
    function requestRedeem(uint256 shares, address controller, address owner)
        external
        returns (uint256 requestId)
    {
        require(shares > 0, "EwpgERC7540: zero shares");
        require(balanceOf(owner) >= shares, "EwpgERC7540: insufficient shares");
        _transfer(owner, address(this), shares);
        requestId = ++_requestCounter;
        _redeemRequests[requestId] = RedeemRequest({ shares: shares, controller: controller, owner: owner, pending: true });
        _requestTimestamps[requestId] = block.timestamp;
        emit RedeemRequested(requestId, controller, owner, shares);
    }

    /// @notice Operator fulfills a pending redemption request at current NAV.
    function fulfillRedeemRequest(uint256 requestId) external onlyRegistry {
        RedeemRequest storage req = _redeemRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(block.timestamp >= _requestTimestamps[requestId] + _minSettlementDelay,
                "EwpgERC7540: settlement delay not elapsed");
        req.pending = false;
        uint256 assets = convertToAssets(req.shares);
        _burn(address(this), req.shares);
        IERC20(asset()).transfer(req.owner, assets);
        emit RedeemRequestFulfilled(requestId, req.shares, assets, currentNavPerShare());
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    function cancelDepositRequest(uint256 requestId) external {
        DepositRequest storage req = _depositRequests[requestId];
        require(req.pending, "EwpgERC7540: not pending");
        require(msg.sender == req.controller || msg.sender == registry,
                "EwpgERC7540: not authorised to cancel");
        req.pending = false;
        IERC20(asset()).transfer(req.owner, req.assets);
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

    // ── View ──────────────────────────────────────────────────────────────────

    function depositRequest(uint256 requestId)
        external view
        returns (uint256 assets, address controller, address owner, bool pending)
    {
        DepositRequest storage req = _depositRequests[requestId];
        return (req.assets, req.controller, req.owner, req.pending);
    }

    function redeemRequest(uint256 requestId)
        external view
        returns (uint256 shares, address controller, address owner, bool pending)
    {
        RedeemRequest storage req = _redeemRequests[requestId];
        return (req.shares, req.controller, req.owner, req.pending);
    }
}
