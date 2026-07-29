// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";
import "./EwpgRepoMarket.sol";
import "./oracle/IRepoOracle.sol";

/// @title EwpgRepoMarketFactory
/// @notice CREATE2 factory for {EwpgRepoMarket} instances — the permissionless-market-creation
///         half of the Morpho Blue analogy: listing a new security as lendable collateral is
///         deploying a new isolated market, never a governance vote that puts existing markets
///         at risk. Deployment itself is gated to the operator (mirrors every other
///         `Ewpg*Factory` in this codebase, e.g. `AssetTokenFactory`) rather than made fully
///         permissionless like Morpho Blue itself, since a market's {collateralToken} here is
///         always a restricted ERC-3643 security, not an arbitrary ERC-20 — creating one is
///         also the trigger for the operator's own follow-up compliance step (flagging the
///         market as a nominee pool on the token's compliance module).
contract EwpgRepoMarketFactory is RegisterwerkGated {
    bytes32 public constant CREATE_MARKET = keccak256("repo-markets.create-market");

    /// @notice permission oracle passed to every deployed market, fixed at factory construction.
    IPermissionOracle public immutable marketOracle;

    address[] public allMarkets;

    event MarketCreated(
        address indexed market,
        address indexed loanToken,
        address indexed collateralToken,
        uint256 lltvBps,
        address priceOracle
    );

    /// @notice `EwpgRepoMarket` itself allows `maxPriceAgeSeconds == 0` (staleness check
    ///         disabled) when constructed directly, for unit testing — but every market this
    ///         factory deploys is a real, operator-approved listing, so a real freshness bound
    ///         is mandatory here. Without it, an operator could accidentally (or a compromised
    ///         operator key could deliberately) launch a market that never rejects a stale NAV
    ///         mark, defeating the staleness guard `_currentPrice()` otherwise enforces.
    error InvalidMaxPriceAge();

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {
        marketOracle = oracle_;
    }

    /// @notice Deploys a new isolated {EwpgRepoMarket} at a deterministic CREATE2 address.
    /// @param loanToken The stablecoin lenders supply and borrowers draw (e.g. AllUnity Euro).
    /// @param collateralToken The single restricted security token this market accepts.
    /// @param priceOracle The {IRepoOracle} this market reads collateral marks from.
    /// @param maxLtvBps Maximum origination LTV, in bps — strictly below `lltvBps`.
    /// @param lltvBps Liquidation LTV, in bps (the health-factor threshold).
    /// @param liquidationBonusBps Extra collateral (bps of debt repaid) awarded to liquidators.
    /// @param baseRateWad Annualized base borrow rate at zero utilization, WAD-scaled.
    /// @param slopeWad Additional annualized rate at 100% utilization, WAD-scaled.
    /// @param maxPriceAgeSeconds Maximum accepted price-mark age. Must be nonzero — see
    ///        {InvalidMaxPriceAge}; `EwpgRepoMarket`'s own "0 disables the check" allowance is
    ///        for direct-construction unit tests only, never for a factory-deployed market.
    /// @param liquidationGracePeriodSeconds Wider staleness tolerance for `liquidate` only; must
    ///        be >= `maxPriceAgeSeconds`.
    /// @return market The address of the newly deployed market.
    function createMarket(
        IERC20 loanToken,
        IERC20 collateralToken,
        IRepoOracle priceOracle,
        uint256 maxLtvBps,
        uint256 lltvBps,
        uint256 liquidationBonusBps,
        uint256 baseRateWad,
        uint256 slopeWad,
        uint256 maxPriceAgeSeconds,
        uint256 liquidationGracePeriodSeconds
    ) external requiresPermission(CREATE_MARKET) returns (address market) {
        if (maxPriceAgeSeconds == 0) revert InvalidMaxPriceAge();
        bytes32 salt = keccak256(abi.encode(loanToken, collateralToken, priceOracle, lltvBps));
        market = address(
            new EwpgRepoMarket{salt: salt}(
                marketOracle,
                loanToken,
                collateralToken,
                priceOracle,
                maxLtvBps,
                lltvBps,
                liquidationBonusBps,
                baseRateWad,
                slopeWad,
                maxPriceAgeSeconds,
                liquidationGracePeriodSeconds
            )
        );
        allMarkets.push(market);
        emit MarketCreated(market, address(loanToken), address(collateralToken), lltvBps, address(priceOracle));
    }

    /// @notice Predicts the CREATE2 address for a market before deploying it — useful for
    ///         pre-authorizing the market as a nominee pool on the collateral token's
    ///         compliance module ahead of time.
    function predictMarketAddress(
        IERC20 loanToken,
        IERC20 collateralToken,
        IRepoOracle priceOracle,
        uint256 maxLtvBps,
        uint256 lltvBps,
        uint256 liquidationBonusBps,
        uint256 baseRateWad,
        uint256 slopeWad,
        uint256 maxPriceAgeSeconds,
        uint256 liquidationGracePeriodSeconds
    ) external view returns (address) {
        bytes32 salt = keccak256(abi.encode(loanToken, collateralToken, priceOracle, lltvBps));
        bytes memory initCode = abi.encodePacked(
            type(EwpgRepoMarket).creationCode,
            abi.encode(
                marketOracle,
                loanToken,
                collateralToken,
                priceOracle,
                maxLtvBps,
                lltvBps,
                liquidationBonusBps,
                baseRateWad,
                slopeWad,
                maxPriceAgeSeconds,
                liquidationGracePeriodSeconds
            )
        );
        bytes32 initCodeHash = keccak256(initCode);
        return address(uint160(uint256(keccak256(abi.encodePacked(bytes1(0xff), address(this), salt, initCodeHash)))));
    }

    /// @notice Number of markets ever deployed by this factory.
    function marketCount() external view returns (uint256) {
        return allMarkets.length;
    }
}
