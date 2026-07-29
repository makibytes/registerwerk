// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

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
contract RegisterwerkNavOracle is RegisterwerkGated, IRepoOracle {
    /// @dev Namespaced under the `repo-markets` marketplace listing's own permission
    ///      namespace (see `ManifestValidationService`'s "a dApp's brand-new permission codes
    ///      must live in its own `<slug>.*` namespace" rule) since this oracle ships as part of
    ///      that listing's manifest — even though, mechanically, one deployed instance can be
    ///      shared read-only by any number of {EwpgRepoMarket}s referencing it.
    bytes32 public constant PUSH_PRICE = keccak256("repo-markets.push-price");

    /// @notice Separately gated from {PUSH_PRICE}: configuring the deviation cap, or pushing a
    ///         price past it, is a deliberate override of the circuit breaker below and must
    ///         not be something an ordinary NAV-feed automation key can do on its own.
    bytes32 public constant OVERRIDE_PRICE = keccak256("repo-markets.override-price");

    uint256 private constant BPS_DENOMINATOR = 10_000;

    struct Mark {
        uint256 pricePerUnit;
        uint256 updatedAt;
    }

    mapping(address => Mark) private _marks;

    /// @notice Maximum allowed relative change (in bps) between consecutive pushed prices for
    ///         a given asset via the ordinary {pushPrice} path. A single compromised or
    ///         fat-fingered `PUSH_PRICE` key can otherwise mark collateral arbitrarily high
    ///         (enabling over-borrowing that drains the pool) or arbitrarily low (triggering
    ///         mass unnecessary liquidations) — this bounds that blast radius to one push.
    ///         Operator-adjustable; defaults to 20%.
    uint256 public maxDeviationBps = 2000;

    event PricePushed(address indexed asset, uint256 pricePerUnit, uint256 updatedAt);
    event MaxDeviationUpdated(uint256 maxDeviationBps);
    event PriceDeviationOverridden(address indexed asset, uint256 previousPrice, uint256 newPrice);

    error ZeroAmount();
    error ExcessiveDeviation(uint256 previousPrice, uint256 newPrice, uint256 maxDeviationBps);

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    /// @notice Sets the maximum relative price change {pushPrice} accepts in one call, in bps.
    function setMaxDeviationBps(uint256 newMaxDeviationBps) external requiresPermission(OVERRIDE_PRICE) {
        maxDeviationBps = newMaxDeviationBps;
        emit MaxDeviationUpdated(newMaxDeviationBps);
    }

    /// @notice Pushes a fresh NAV/last-fill mark for `asset`. Gated to any org holding
    ///         `repo-markets.push-price` — typically the operator, a delegated fund
    ///         administrator, or the operator of a `CompliantSecondaryMarket` desk trading the
    ///         same asset. Reverts if the new price deviates from the previous mark by more
    ///         than {maxDeviationBps} — the first-ever push for an asset is unbounded, since
    ///         there is no prior mark to compare against. A legitimate large repricing must go
    ///         through {pushPriceWithOverride} instead.
    function pushPrice(address asset, uint256 pricePerUnit) external requiresPermission(PUSH_PRICE) {
        if (pricePerUnit == 0) revert ZeroAmount();
        uint256 previous = _marks[asset].pricePerUnit;
        if (previous != 0) {
            uint256 diff = pricePerUnit > previous ? pricePerUnit - previous : previous - pricePerUnit;
            uint256 deviationBps = (diff * BPS_DENOMINATOR) / previous;
            if (deviationBps > maxDeviationBps) {
                revert ExcessiveDeviation(previous, pricePerUnit, maxDeviationBps);
            }
        }
        _marks[asset] = Mark(pricePerUnit, block.timestamp);
        emit PricePushed(asset, pricePerUnit, block.timestamp);
    }

    /// @notice Pushes a price past the normal {maxDeviationBps} circuit breaker — for a
    ///         legitimate large NAV correction (or to fix an earlier mis-pushed mark).
    ///         Separately gated by {OVERRIDE_PRICE} so an ordinary NAV-feed key cannot bypass
    ///         the deviation cap on its own.
    function pushPriceWithOverride(address asset, uint256 pricePerUnit) external requiresPermission(OVERRIDE_PRICE) {
        if (pricePerUnit == 0) revert ZeroAmount();
        uint256 previous = _marks[asset].pricePerUnit;
        _marks[asset] = Mark(pricePerUnit, block.timestamp);
        emit PriceDeviationOverridden(asset, previous, pricePerUnit);
        emit PricePushed(asset, pricePerUnit, block.timestamp);
    }

    /// @inheritdoc IRepoOracle
    function price(address asset) external view returns (uint256 pricePerUnit, uint256 updatedAt) {
        Mark storage mark = _marks[asset];
        return (mark.pricePerUnit, mark.updatedAt);
    }
}
