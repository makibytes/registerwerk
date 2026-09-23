// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import "../../ecosystem/RegisterwerkGated.sol";
import "../../ecosystem/interfaces/IPermissionOracle.sol";
import "./IRepoOracle.sol";

/// @title RegisterwerkNavOracle
/// @notice Reference {IRepoOracle}: an operator/agent-pushed NAV mark shared across every
///         `EwpgRepoMarket` that references it, so one NAV administrator (or a
///         `CompliantSecondaryMarket` desk relaying its own last executed fill) updates one
///         place instead of every market for a given collateral. Mirrors
///         `EwpgRepoFacility.updatePrice`'s push model exactly, formalized as a standalone,
///         swappable oracle contract — see {IRepoOracle} for why a push model is the correct
///         (and currently only available) choice for a NAV-priced security.
///
///         One instance serves one quote currency ({quoteToken}) and is operated by one org
///         ({operatorOrg}). Each asset has exactly one pushing org ({pusherOf}); a
///         `repo-markets.push-price` grant held by any other org cannot move that asset's mark.
contract RegisterwerkNavOracle is RegisterwerkGated, IRepoOracle {
    /// @dev Namespaced under the `repo-markets` marketplace listing's own permission
    ///      namespace (see `ManifestValidationService`'s "a dApp's brand-new permission codes
    ///      must live in its own `<slug>.*` namespace" rule) since this oracle ships as part of
    ///      that listing's manifest — even though, mechanically, one deployed instance can be
    ///      shared read-only by any number of {EwpgRepoMarket}s referencing it.
    bytes32 public constant PUSH_PRICE = keccak256("repo-markets.push-price");

    /// @notice Separately gated from {PUSH_PRICE}: tightening the deviation cap, assigning an
    ///         asset's pusher, or pushing a price past the cap is a deliberate override of the
    ///         circuit breaker below and must not be something an ordinary NAV-feed automation
    ///         key can do on its own. Only members of {operatorOrg} may exercise it.
    bytes32 public constant OVERRIDE_PRICE = keccak256("repo-markets.override-price");

    uint256 private constant BPS_DENOMINATOR = 10_000;
    uint256 private constant MAX_DEVIATION_WINDOW = 365 days;

    struct Mark {
        uint256 pricePerUnit;
        uint256 updatedAt;
    }

    /// @dev The current deviation window of one asset: when it opened and the lowest/highest
    ///      mark accepted inside it. It opens at the mark current at that time (its anchor).
    struct DeviationWindow {
        uint256 since;
        uint256 low;
        uint256 high;
    }

    /// @notice The org (ONCHAINID address) operating this oracle instance: sole holder of the
    ///         override path, and the pusher of every asset without an explicit {pusherOf}.
    address public immutable operatorOrg;

    /// @inheritdoc IRepoOracle
    address public immutable quoteToken;

    /// @notice Decimals of {quoteToken}, read from the token at construction — every mark is
    ///         a count of `10**-quoteDecimals` quote units per collateral unit.
    uint8 public immutable quoteDecimals;

    /// @notice Length of one per-asset deviation window, in seconds. All ordinary pushes for an
    ///         asset inside one window together stay within {maxDeviationBps}; the window
    ///         re-anchors on the current mark with the first push after it has elapsed.
    uint256 public immutable deviationWindow;

    /// @notice Minimum spacing between two ordinary pushes for one asset, in seconds (0 = off).
    ///         A non-zero spacing makes a push-and-restore visible to any monitor sampling marks,
    ///         because the restore cannot land in the same block.
    uint256 public immutable minPushInterval;

    mapping(address => Mark) private _marks;
    mapping(address => DeviationWindow) private _windows;
    mapping(address => address) private _pusherOrg;

    /// @notice Maximum drop (in bps) from the highest mark to the lowest mark accepted for an
    ///         asset within one {deviationWindow} via the ordinary {pushPrice} path — i.e. no
    ///         sequence of ordinary pushes, however many and however fast, can move a mark
    ///         down by more than this from any mark earlier in the same window (and never up
    ///         by more than `maxDeviationBps / (1 − maxDeviationBps)`). This bounds the blast
    ///         radius of a compromised or fat-fingered `PUSH_PRICE` key to one window. It can
    ///         only ever be lowered ({setMaxDeviationBps}): markets check their liquidation
    ///         haircut against it once, at creation, so a wider cap needs a new oracle and
    ///         new markets.
    uint256 public maxDeviationBps;

    event PricePushed(address indexed asset, uint256 pricePerUnit, uint256 updatedAt);
    event MaxDeviationUpdated(uint256 maxDeviationBps);
    event PriceDeviationOverridden(address indexed asset, uint256 previousPrice, uint256 newPrice);
    event AssetPusherSet(address indexed asset, address indexed pusherOrg);

    error ZeroAmount();
    error ZeroQuoteToken();
    error InvalidDeviationWindow(uint256 deviationWindow);
    error InvalidMaxDeviation(uint256 maxDeviationBps);
    /// @notice `referencePrice` is the window's high for a drop and the window's low for a rise.
    error ExcessiveDeviation(uint256 referencePrice, uint256 newPrice, uint256 maxDeviationBps);
    error PushTooSoon(address asset, uint256 nextPushAt);
    error DeviationCapIncrease(uint256 currentMaxDeviationBps, uint256 requestedMaxDeviationBps);

    /// @param operatorOrg_ Org operating this instance (see {operatorOrg}).
    /// @param quoteToken_ The loan token every mark is denominated in — one oracle per rail.
    /// @param maxDeviationBps_ Initial (and highest ever) {maxDeviationBps}, ≤ 10_000; 2000 = 20%.
    /// @param deviationWindow_ {deviationWindow}; 1 day in production, shorter for demos.
    /// @param minPushInterval_ {minPushInterval}; 0 disables the spacing check.
    constructor(
        IPermissionOracle oracle_,
        address operatorOrg_,
        address quoteToken_,
        uint256 maxDeviationBps_,
        uint256 deviationWindow_,
        uint256 minPushInterval_
    ) RegisterwerkGated(oracle_) {
        _requireOrg(operatorOrg_);
        if (quoteToken_ == address(0)) revert ZeroQuoteToken();
        if (deviationWindow_ == 0 || deviationWindow_ > MAX_DEVIATION_WINDOW) {
            revert InvalidDeviationWindow(deviationWindow_);
        }
        if (minPushInterval_ > MAX_DEVIATION_WINDOW) revert InvalidDeviationWindow(minPushInterval_);
        if (maxDeviationBps_ > BPS_DENOMINATOR) revert InvalidMaxDeviation(maxDeviationBps_);
        operatorOrg = operatorOrg_;
        quoteToken = quoteToken_;
        quoteDecimals = IERC20Metadata(quoteToken_).decimals();
        maxDeviationBps = maxDeviationBps_;
        deviationWindow = deviationWindow_;
        minPushInterval = minPushInterval_;
    }

    /// @notice Lowers the maximum per-window deviation {pushPrice} accepts, in bps. Raising it
    ///         is refused: every market referencing this oracle validated its liquidation
    ///         haircut against the cap in force at its creation.
    function setMaxDeviationBps(uint256 newMaxDeviationBps)
        external
        requiresOrgPermission(operatorOrg, OVERRIDE_PRICE)
    {
        if (newMaxDeviationBps > maxDeviationBps) {
            revert DeviationCapIncrease(maxDeviationBps, newMaxDeviationBps);
        }
        maxDeviationBps = newMaxDeviationBps;
        emit MaxDeviationUpdated(newMaxDeviationBps);
    }

    /// @notice Assigns the org allowed to push ordinary marks for `asset` — e.g. the delegated
    ///         fund administrator of that one security. `pusherOrg == address(0)` returns the
    ///         asset to the default, {operatorOrg}.
    function setAssetPusher(address asset, address pusherOrg)
        external
        requiresOrgPermission(operatorOrg, OVERRIDE_PRICE)
    {
        _pusherOrg[asset] = pusherOrg;
        emit AssetPusherSet(asset, pusherOrg);
    }

    /// @notice The org whose members (holding {PUSH_PRICE}) may push ordinary marks for `asset`.
    function pusherOf(address asset) public view returns (address) {
        address pusher = _pusherOrg[asset];
        return pusher == address(0) ? operatorOrg : pusher;
    }

    /// @notice Pushes a fresh NAV/last-fill mark for `asset`. Gated to members of the asset's
    ///         {pusherOf} org holding `repo-markets.push-price`. Reverts if, together with the
    ///         marks already accepted in the asset's current {deviationWindow}, the new price
    ///         spans more than {maxDeviationBps} (see there), or if it comes sooner than
    ///         {minPushInterval} after the previous mark. The first-ever push for an asset is
    ///         unbounded, since there is no prior mark to compare against. A legitimate large
    ///         repricing must go through {pushPriceWithOverride} instead.
    function pushPrice(address asset, uint256 pricePerUnit)
        external
        requiresOrgPermission(pusherOf(asset), PUSH_PRICE)
    {
        if (pricePerUnit == 0) revert ZeroAmount();
        Mark storage mark = _marks[asset];
        DeviationWindow storage window = _windows[asset];
        if (mark.pricePerUnit == 0) {
            _openWindow(window, pricePerUnit);
        } else {
            if (block.timestamp < mark.updatedAt + minPushInterval) {
                revert PushTooSoon(asset, mark.updatedAt + minPushInterval);
            }
            if (block.timestamp >= window.since + deviationWindow) {
                _openWindow(window, mark.pricePerUnit);
            }
            uint256 low = pricePerUnit < window.low ? pricePerUnit : window.low;
            uint256 high = pricePerUnit > window.high ? pricePerUnit : window.high;
            if ((high - low) * BPS_DENOMINATOR > maxDeviationBps * high) {
                uint256 referencePrice = pricePerUnit < window.low ? window.high : window.low;
                revert ExcessiveDeviation(referencePrice, pricePerUnit, maxDeviationBps);
            }
            window.low = low;
            window.high = high;
        }
        _marks[asset] = Mark(pricePerUnit, block.timestamp);
        emit PricePushed(asset, pricePerUnit, block.timestamp);
    }

    /// @notice Pushes a price past the normal {maxDeviationBps} circuit breaker — for a
    ///         legitimate large NAV correction (or to fix an earlier mis-pushed mark) — and
    ///         re-anchors the asset's deviation window on it. Separately gated by
    ///         {OVERRIDE_PRICE} and bound to {operatorOrg}, so neither an ordinary NAV-feed key
    ///         nor another org's override grant can bypass the deviation cap.
    function pushPriceWithOverride(address asset, uint256 pricePerUnit)
        external
        requiresOrgPermission(operatorOrg, OVERRIDE_PRICE)
    {
        if (pricePerUnit == 0) revert ZeroAmount();
        uint256 previous = _marks[asset].pricePerUnit;
        _marks[asset] = Mark(pricePerUnit, block.timestamp);
        _openWindow(_windows[asset], pricePerUnit);
        emit PriceDeviationOverridden(asset, previous, pricePerUnit);
        emit PricePushed(asset, pricePerUnit, block.timestamp);
    }

    /// @inheritdoc IRepoOracle
    function price(address asset) external view returns (uint256 pricePerUnit, uint256 updatedAt) {
        Mark storage mark = _marks[asset];
        return (mark.pricePerUnit, mark.updatedAt);
    }

    /// @notice The asset's current deviation window: opening time and the lowest/highest mark
    ///         accepted in it. A window older than {deviationWindow} is re-anchored on the next push.
    function deviationWindowOf(address asset) external view returns (uint256 since, uint256 low, uint256 high) {
        DeviationWindow storage window = _windows[asset];
        return (window.since, window.low, window.high);
    }

    function _openWindow(DeviationWindow storage window, uint256 anchorPrice) private {
        window.since = block.timestamp;
        window.low = anchorPrice;
        window.high = anchorPrice;
    }
}
