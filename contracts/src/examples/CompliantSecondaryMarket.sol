// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";
import "../settlement/DvpSettlement.sol";

/// @title CompliantSecondaryMarket
/// @notice Reference marketplace dApp: a nominee/omnibus secondary-market desk for
///         ERC-3643 (T-REX) security tokens. This contract's own address *is* the nominee
///         pool — the token's registry agent flags it via
///         `EwpgComplianceModule.setNomineePool(token, address(this), true)` once its
///         operator holds a valid NOMINEE claim (topic 4) on its org's ONCHAINID, exempting
///         it from `maxBalancePerInvestor`/`maxInvestors` so it can accumulate pooled
///         inventory on behalf of investors it KYCs and tracks in its own off-chain
///         sub-ledger (see `docs/platform/defi-interoperability.md`).
///
/// @dev Deliberately an RFQ/bilateral-matching desk, not a bonding-curve AMM: security
///      tokens are NAV-priced and potentially illiquid, so a shared constant-product curve
///      would expose LPs to impermanent loss and make the price trivially manipulable.
///      Each trade is a discrete, operator-priced quote settled in one successful transaction
///      through the operator-provided {DvpSettlement} escrow. Exact-leg behavior assumes tokens
///      without transfer fees/rebases; chain finality and legal-register reconciliation are
///      separate. This contract never re-implements
///      escrow logic itself, it only authorizes the pool's own inventory to move:
///        - {sellFromInventory} escrows security tokens the pool already holds via
///          `DvpSettlement.lockAsset`; the counterparty investor completes by calling
///          `DvpSettlement.settle` themselves (pays, receives the escrowed tokens).
///        - {buyIntoInventory} escrows payment-token capital via
///          `DvpSettlement.lockPayment`; the counterparty investor completes by calling
///          `DvpSettlement.settle` themselves (delivers tokens, receives the escrowed
///          payment) — this is the leg that grows the pool's onchain inventory and is
///          exactly where the nominee exemption in `EwpgComplianceModule.moduleCheck`
///          applies.
///      Permission surface (namespace "secondary-market."), gated by {RegisterwerkGated}:
///      only a wallet whose org holds `secondary-market.trade` AND a valid NOMINEE claim
///      may operate the pool's inventory — never a self-declared property of the pool.
contract CompliantSecondaryMarket is RegisterwerkGated {
    using SafeERC20 for IERC20;

    bytes32 public constant TRADE = keccak256("secondary-market.trade");
    uint256 public constant TOPIC_NOMINEE = 4;

    /// @notice The MiCAR EMT stablecoin every payment leg settles in.
    IERC20 public immutable paymentToken;

    /// @notice The operator-provided ERC-7573-style escrow every trade settles through.
    DvpSettlement public immutable settlement;

    event SoldFromInventory(bytes32 indexed tradeId, address indexed buyer, address indexed securityToken, uint256 assetAmount, uint256 paymentAmount);
    event BoughtIntoInventory(bytes32 indexed tradeId, address indexed seller, address indexed securityToken, uint256 assetAmount, uint256 paymentAmount);

    error ZeroAddress();

    constructor(IPermissionOracle oracle_, IERC20 paymentToken_, DvpSettlement settlement_) RegisterwerkGated(oracle_) {
        if (address(paymentToken_) == address(0) || address(settlement_) == address(0)) revert ZeroAddress();
        paymentToken = paymentToken_;
        settlement = settlement_;
    }

    /// @notice Sell `assetAmount` of `securityToken` from the pool's own inventory to
    ///         `buyer`, escrowed via {DvpSettlement.lockAsset}. `buyer` pays `paymentAmount`
    ///         and receives the escrowed tokens by calling `DvpSettlement.settle` before
    ///         `expiry`. Reverts at the T-REX layer if `buyer` is not a verified,
    ///         compliant recipient — this desk does not bypass that check.
    function sellFromInventory(
        bytes32 tradeId,
        address buyer,
        IERC20 securityToken,
        uint256 assetAmount,
        uint256 paymentAmount,
        uint64 expiry
    ) external requiresPermission(TRADE) requiresClaim(TOPIC_NOMINEE) {
        securityToken.forceApprove(address(settlement), assetAmount);
        settlement.lockAsset(tradeId, buyer, securityToken, assetAmount, paymentToken, paymentAmount, expiry);
        emit SoldFromInventory(tradeId, buyer, address(securityToken), assetAmount, paymentAmount);
    }

    /// @notice Buy `assetAmount` of `securityToken` from `seller` into the pool's
    ///         inventory, escrowing `paymentAmount` via {DvpSettlement.lockPayment}.
    ///         `seller` delivers the tokens and receives the escrowed payment by calling
    ///         `DvpSettlement.settle` before `expiry`. This is the leg that grows the
    ///         pool's own onchain balance of `securityToken` — see the contract-level
    ///         NatSpec on why that requires a nominee exemption at the compliance layer.
    function buyIntoInventory(
        bytes32 tradeId,
        address seller,
        IERC20 securityToken,
        uint256 assetAmount,
        uint256 paymentAmount,
        uint64 expiry
    ) external requiresPermission(TRADE) requiresClaim(TOPIC_NOMINEE) {
        paymentToken.forceApprove(address(settlement), paymentAmount);
        settlement.lockPayment(tradeId, seller, securityToken, assetAmount, paymentToken, paymentAmount, expiry);
        emit BoughtIntoInventory(tradeId, seller, address(securityToken), assetAmount, paymentAmount);
    }
}
