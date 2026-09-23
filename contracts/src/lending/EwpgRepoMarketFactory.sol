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
///         also triggers the operator's compliance step (flagging the
///         market as a nominee pool on the token's compliance module).
///
///         Every market is bound to the org of the wallet that created it (its
///         `EwpgRepoMarket.operatorOrg`), so a same-slug grant held by another org can never
///         administer it. {isMarket} lets vaults and other integrators check that an address is
///         a genuine market from this factory.
contract EwpgRepoMarketFactory is RegisterwerkGated {
    bytes32 public constant CREATE_MARKET = keccak256("repo-markets.create-market");

    /// @notice permission oracle passed to every deployed market, fixed at factory construction.
    IPermissionOracle public immutable marketOracle;

    address[] public allMarkets;
    /// @notice True for every market this factory deployed.
    mapping(address => bool) public isMarket;

    /// @notice `operatorOrg`/`treasury` are in {MarketOperatorSet}; this event's shape is
    ///         unchanged for existing indexers.
    event MarketCreated(
        address indexed market,
        address indexed loanToken,
        address indexed collateralToken,
        uint256 lltvBps,
        address priceOracle
    );
    event MarketOperatorSet(address indexed market, address indexed operatorOrg, address treasury);

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

    /// @notice Deploys a new isolated {EwpgRepoMarket} at a deterministic CREATE2 address,
    ///         operated by the caller's org.
    /// @param p The market's parameters (see {MarketParams} and the matching
    ///        {EwpgRepoMarket} immutables). `p.operatorOrg` must be the caller's own org —
    ///        a market is always operated by the org that created it. `p.maxPriceAgeSeconds`
    ///        must be nonzero — see {InvalidMaxPriceAge}; `EwpgRepoMarket`'s own "0 disables
    ///        the check" allowance is for direct-construction unit tests only, never for a
    ///        factory-deployed market.
    /// @return market The address of the newly deployed market.
    function createMarket(MarketParams calldata p) external requiresPermission(CREATE_MARKET) returns (address market) {
        if (p.maxPriceAgeSeconds == 0) revert InvalidMaxPriceAge();
        if (p.operatorOrg != marketOracle.orgOf(msg.sender)) revert WrongOperatingOrg(msg.sender, p.operatorOrg);
        market = address(new EwpgRepoMarket{salt: _salt(p)}(marketOracle, p));
        allMarkets.push(market);
        isMarket[market] = true;
        emit MarketCreated(market, address(p.loanToken), address(p.collateralToken), p.lltvBps, address(p.priceOracle));
        emit MarketOperatorSet(market, p.operatorOrg, p.treasury);
    }

    /// @notice Predicts the CREATE2 address {createMarket} will deploy `p` at — useful for
    ///         pre-authorizing the market as a nominee pool on the collateral token's
    ///         compliance module ahead of time.
    function predictMarketAddress(MarketParams calldata p) external view returns (address) {
        bytes32 initCodeHash =
            keccak256(abi.encodePacked(type(EwpgRepoMarket).creationCode, abi.encode(marketOracle, p)));
        return
            address(uint160(uint256(keccak256(abi.encodePacked(bytes1(0xff), address(this), _salt(p), initCodeHash)))));
    }

    function _salt(MarketParams calldata p) private pure returns (bytes32) {
        return keccak256(abi.encode(p.loanToken, p.collateralToken, p.priceOracle, p.lltvBps));
    }

    /// @notice Number of markets ever deployed by this factory.
    function marketCount() external view returns (uint256) {
        return allMarkets.length;
    }
}
