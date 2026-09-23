// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

/// @title IRepoOracle
/// @notice Price feed for {EwpgRepoMarket} collateral marks — formalizes the operator/agent
///         NAV-push pattern already used by `EwpgRepoFacility.updatePrice` into a reusable,
///         swappable interface. A NAV-priced, illiquid security has no continuous on-chain
///         price discovery, so every implementation here is necessarily a push oracle fed by
///         an off-chain NAV administrator or the last executed fill on a
///         `CompliantSecondaryMarket` desk — never a spot AMM price, for the same reasons
///         `docs/platform/defi-interoperability.md` rules out an AMM for the security leg. A
///         future RedStone-/Chainlink-style external feed can implement this interface without
///         any {EwpgRepoMarket} change, since the market only ever depends on this interface.
interface IRepoOracle {
    /// @notice Current price of one unit of `asset`, denominated in {quoteToken} base units,
    ///         plus the timestamp of the last update. `pricePerUnit == 0` means "not yet
    ///         priced" and callers must treat the asset as unbo­rrowable against.
    function price(address asset) external view returns (uint256 pricePerUnit, uint256 updatedAt);

    /// @notice The token every mark of this oracle is denominated in (its base units). One
    ///         oracle serves exactly one quote currency, so a market must only reference an
    ///         oracle whose quote token is its own loan token — a EUR mark read by a USDC
    ///         market, or a 6-decimal mark read by an 18-decimal market, misprices collateral.
    function quoteToken() external view returns (address);

    /// @notice Maximum cumulative relative move (in bps) this oracle tolerates for one asset
    ///         within one deviation window before requiring an explicit override (see
    ///         {RegisterwerkNavOracle.maxDeviationBps}). The bound must hold for any sequence of
    ///         ordinary pushes, not just one, and it must never increase over the oracle's
    ///         lifetime: `EwpgRepoMarket` cross-checks it against a market's liquidation haircut
    ///         at creation time — a haircut thinner than this tolerance means ordinary,
    ///         in-tolerance price pushes could already leave a liquidation under-collateralized.
    ///         An oracle with no such concept (e.g. a future continuous feed) may return
    ///         `type(uint256).max` to opt out of the check.
    function maxDeviationBps() external view returns (uint256);
}
