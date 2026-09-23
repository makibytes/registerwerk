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
///      only a wallet bound to this instance's {operatorOrg} whose org holds
///      `secondary-market.trade` AND a valid NOMINEE claim may operate the pool's
///      inventory — never a self-declared property of the pool. The slug grant alone is
///      not enough: another nominee org holding the same code runs its own desk instance
///      and cannot touch this one's inventory.
///
///      Trade ids are derived by {DvpSettlement} from this contract (the locker) and a
///      caller-supplied `clientRef`; the counterparty settles with
///      `DvpSettlement.settle(tradeId, hashTerms(...))`. If the counterparty never settles
///      or renounces, {reclaimExpired} returns the escrowed leg to the pool after expiry.
contract CompliantSecondaryMarket is RegisterwerkGated {
    using SafeERC20 for IERC20;

    bytes32 public constant TRADE = keccak256("secondary-market.trade");
    uint256 public constant TOPIC_NOMINEE = 4;

    /// @notice The org (its ONCHAINID address) that operates this desk instance.
    address public immutable operatorOrg;

    /// @notice The MiCAR EMT stablecoin every payment leg settles in.
    IERC20 public immutable paymentToken;

    /// @notice The operator-provided ERC-7573-style escrow every trade settles through.
    DvpSettlement public immutable settlement;

    event SoldFromInventory(bytes32 indexed tradeId, address indexed buyer, address indexed securityToken, uint256 assetAmount, uint256 paymentAmount);
    event BoughtIntoInventory(bytes32 indexed tradeId, address indexed seller, address indexed securityToken, uint256 assetAmount, uint256 paymentAmount);
    event ExpiredEscrowReclaimed(bytes32 indexed tradeId);

    error ZeroAddress();

    constructor(IPermissionOracle oracle_, address operatorOrg_, IERC20 paymentToken_, DvpSettlement settlement_)
        RegisterwerkGated(oracle_)
    {
        _requireOrg(operatorOrg_);
        if (address(paymentToken_) == address(0) || address(settlement_) == address(0)) revert ZeroAddress();
        operatorOrg = operatorOrg_;
        paymentToken = paymentToken_;
        settlement = settlement_;
    }

    /// @notice Sell `assetAmount` of `securityToken` from the pool's own inventory to
    ///         `buyer`, escrowed via {DvpSettlement.lockAsset}. `buyer` pays `paymentAmount`
    ///         and receives the escrowed tokens by calling `DvpSettlement.settle` before
    ///         `expiry`. Reverts at the T-REX layer if `buyer` is not a verified,
    ///         compliant recipient — this desk does not bypass that check.
    /// @return tradeId The id {DvpSettlement} derived for this desk and `clientRef`.
    function sellFromInventory(
        bytes32 clientRef,
        address buyer,
        IERC20 securityToken,
        uint256 assetAmount,
        uint256 paymentAmount,
        uint64 expiry
    ) external requiresOrgPermission(operatorOrg, TRADE) requiresClaim(TOPIC_NOMINEE) returns (bytes32 tradeId) {
        securityToken.forceApprove(address(settlement), assetAmount);
        tradeId = settlement.lockAsset(clientRef, buyer, securityToken, assetAmount, paymentToken, paymentAmount, expiry);
        emit SoldFromInventory(tradeId, buyer, address(securityToken), assetAmount, paymentAmount);
    }

    /// @notice Buy `assetAmount` of `securityToken` from `seller` into the pool's
    ///         inventory, escrowing `paymentAmount` via {DvpSettlement.lockPayment}.
    ///         `seller` delivers the tokens and receives the escrowed payment by calling
    ///         `DvpSettlement.settle` before `expiry`. This is the leg that grows the
    ///         pool's own onchain balance of `securityToken` — see the contract-level
    ///         NatSpec on why that requires a nominee exemption at the compliance layer.
    /// @return tradeId The id {DvpSettlement} derived for this desk and `clientRef`.
    function buyIntoInventory(
        bytes32 clientRef,
        address seller,
        IERC20 securityToken,
        uint256 assetAmount,
        uint256 paymentAmount,
        uint64 expiry
    ) external requiresOrgPermission(operatorOrg, TRADE) requiresClaim(TOPIC_NOMINEE) returns (bytes32 tradeId) {
        paymentToken.forceApprove(address(settlement), paymentAmount);
        tradeId = settlement.lockPayment(clientRef, seller, securityToken, assetAmount, paymentToken, paymentAmount, expiry);
        emit BoughtIntoInventory(tradeId, seller, address(securityToken), assetAmount, paymentAmount);
    }

    /// @notice Returns an expired, unsettled inventory escrow of this desk to the pool via
    ///         `DvpSettlement.cancel` — this contract is the locker, so nobody else can.
    function reclaimExpired(bytes32 tradeId)
        external
        requiresOrgPermission(operatorOrg, TRADE)
        requiresClaim(TOPIC_NOMINEE)
    {
        settlement.cancel(tradeId);
        emit ExpiredEscrowReclaimed(tradeId);
    }
}
