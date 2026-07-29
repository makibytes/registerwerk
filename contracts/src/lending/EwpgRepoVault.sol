// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/extensions/ERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";
import "./EwpgRepoMarket.sol";

/// @title EwpgRepoVault
/// @notice ERC-4626 curator vault that allocates lender deposits across multiple
///         {EwpgRepoMarket} isolated markets — the MetaMorpho-style layer on top of
///         Morpho-Blue-style isolated markets. A depositor who wants diversified, passively
///         managed exposure supplies once here instead of choosing and monitoring individual
///         markets directly (which remains available, ungated, exactly as before — this vault
///         is an additional option, not a replacement). The vault's own deposit/withdraw side
///         is likewise ungated: it never touches the restricted collateral asset, only the
///         shared stablecoin, same as every market's lender side.
///
/// @dev MVP curator model: an org holding `repo-markets.curate-vault` (the operator, or a
///      delegated curator) adds/removes markets and caps, and moves idle cash into/out of a
///      market via {allocate}/{deallocate}. Withdrawals are served from idle cash only —
///      {maxWithdraw}/{maxRedeem} do not walk the market list to free up liquidity. This is a
///      deliberate MVP boundary, not a full withdrawal queue/waterfall like production
///      MetaMorpho; the curator is expected to keep enough idle buffer for expected
///      redemptions, and a cross-market withdrawal queue is a natural v2 refinement — see
///      `docs/platform/defi-interoperability.md`-style "what's implemented vs. what needs
///      more work" framing applied the same way here.
///
///      {totalAssets} sums `balanceOf` across every allocated market unconditionally — this
///      means a bad-debt write-off recognized in any one market (see
///      {EwpgRepoMarket-BadDebtRecognized}) is reflected here too, proportionally diluting
///      every vault depositor's share price, not just that market's own direct lenders. A
///      depositor who chose this vault specifically for diversified,
///      loss-isolated exposure across markets does not currently get that isolation once
///      capital is allocated — a per-market loss-containment/tranching model is a natural v2
///      refinement, tracked alongside the withdrawal-queue gap above, not yet implemented here.
contract EwpgRepoVault is ERC4626, RegisterwerkGated, ReentrancyGuard {
    using SafeERC20 for IERC20;

    bytes32 public constant CURATE = keccak256("repo-markets.curate-vault");

    struct MarketAllocation {
        bool enabled;
        uint256 capWad; // max the vault will ever allocate into this market, in loan-token units
    }

    /// @notice Every market ever added (including later-disabled ones — see {marketCount}).
    EwpgRepoMarket[] public marketList;
    mapping(address => MarketAllocation) public allocations;
    mapping(address => bool) private _marketEverAdded;

    event MarketAdded(address indexed market, uint256 capWad);
    event MarketCapUpdated(address indexed market, uint256 capWad);
    event MarketRemoved(address indexed market);
    event Allocated(address indexed market, uint256 amount);
    event Deallocated(address indexed market, uint256 amount);

    error MarketNotEnabled(address market);
    error MarketAlreadyAdded(address market);
    error ExceedsMarketCap(address market);
    error LoanTokenMismatch(address market);
    error ZeroAddress();

    constructor(IPermissionOracle oracle_, IERC20 loanToken_, string memory name_, string memory symbol_)
        ERC4626(loanToken_)
        ERC20(name_, symbol_)
        RegisterwerkGated(oracle_)
    {}

    // ── Reentrancy-guarded ERC-4626 entry points ─────────────────────────────
    //
    // The base ERC4626 deposit/mint/withdraw/redeem functions call out to the underlying
    // token (transferFrom/transfer) and, for withdraw/redeem, burn shares around that external
    // call — the standard reentrancy shape every other value-moving function in this lending
    // stack (`EwpgRepoMarket`, `EwpgRepoFacility`) already guards with `nonReentrant`. This
    // vault held the same external-call shape without the guard; these overrides close that gap
    // by wrapping the OZ implementation rather than reimplementing it.

    function deposit(uint256 assets, address receiver) public override nonReentrant returns (uint256) {
        return super.deposit(assets, receiver);
    }

    function mint(uint256 shares, address receiver) public override nonReentrant returns (uint256) {
        return super.mint(shares, receiver);
    }

    function withdraw(uint256 assets, address receiver, address owner)
        public
        override
        nonReentrant
        returns (uint256)
    {
        return super.withdraw(assets, receiver, owner);
    }

    function redeem(uint256 shares, address receiver, address owner)
        public
        override
        nonReentrant
        returns (uint256)
    {
        return super.redeem(shares, receiver, owner);
    }

    /// @notice Total assets under management: idle cash held directly plus the vault's current
    ///         claim (principal + accrued interest) in every allocated market.
    function totalAssets() public view override returns (uint256 total) {
        total = IERC20(asset()).balanceOf(address(this));
        uint256 len = marketList.length;
        for (uint256 i = 0; i < len; i++) {
            total += marketList[i].balanceOf(address(this));
        }
    }

    /// @notice Registers a new market this vault may allocate into, with its allocation cap.
    ///         Reverts if the market's {EwpgRepoMarket.loanToken} does not match this vault's
    ///         underlying asset.
    function addMarket(EwpgRepoMarket market, uint256 capWad) external requiresPermission(CURATE) {
        if (address(market) == address(0)) revert ZeroAddress();
        if (address(market.loanToken()) != asset()) revert LoanTokenMismatch(address(market));
        if (allocations[address(market)].enabled) revert MarketAlreadyAdded(address(market));
        allocations[address(market)] = MarketAllocation(true, capWad);
        // Disabled markets remain in marketList so an outstanding position is
        // still included in totalAssets. Re-enabling one must not append it again
        // or the same position would be valued multiple times.
        if (!_marketEverAdded[address(market)]) {
            _marketEverAdded[address(market)] = true;
            marketList.push(market);
        }
        emit MarketAdded(address(market), capWad);
    }

    /// @notice Updates the allocation cap for an already-added market.
    function setMarketCap(EwpgRepoMarket market, uint256 capWad) external requiresPermission(CURATE) {
        if (!allocations[address(market)].enabled) revert MarketNotEnabled(address(market));
        allocations[address(market)].capWad = capWad;
        emit MarketCapUpdated(address(market), capWad);
    }

    /// @notice Disables further allocation into `market`. Does not forcibly withdraw existing
    ///         principal — call {deallocate} first to unwind an existing position.
    function removeMarket(EwpgRepoMarket market) external requiresPermission(CURATE) {
        if (!allocations[address(market)].enabled) revert MarketNotEnabled(address(market));
        allocations[address(market)].enabled = false;
        emit MarketRemoved(address(market));
    }

    /// @notice Moves `amount` of idle vault cash into `market`'s lender side, up to that
    ///         market's configured cap.
    function allocate(EwpgRepoMarket market, uint256 amount) external nonReentrant requiresPermission(CURATE) {
        MarketAllocation storage alloc = allocations[address(market)];
        if (!alloc.enabled) revert MarketNotEnabled(address(market));
        uint256 currentPosition = market.balanceOf(address(this));
        if (currentPosition + amount > alloc.capWad) revert ExceedsMarketCap(address(market));
        IERC20(asset()).forceApprove(address(market), amount);
        market.supply(amount);
        emit Allocated(address(market), amount);
    }

    /// @notice Withdraws `amount` of the vault's supply position out of `market`, back to idle
    ///         cash, subject to that market's own available (unborrowed) liquidity.
    function deallocate(EwpgRepoMarket market, uint256 amount) external nonReentrant requiresPermission(CURATE) {
        if (!allocations[address(market)].enabled && market.balanceOf(address(this)) == 0) {
            revert MarketNotEnabled(address(market));
        }
        market.withdraw(amount);
        emit Deallocated(address(market), amount);
    }

    /// @notice Number of markets ever added (including later-disabled ones).
    function marketCount() external view returns (uint256) {
        return marketList.length;
    }

    // ── Idle-liquidity withdrawal ceiling ────────────────────────────────────
    //
    // OpenZeppelin's default {maxWithdraw}/{maxRedeem} report the owner's full economic claim,
    // not what the vault can actually pay out right now. Since this MVP only ever holds cash
    // as idle balance or supplied into a market (see the contract-level NatSpec), the real
    // ceiling is idle cash — without this override, a withdrawal beyond idle liquidity would
    // simply revert deep inside `_withdraw`'s token transfer instead of being reported
    // up front via the standard ERC-4626 `maxWithdraw`/`maxRedeem` views.

    function maxWithdraw(address owner) public view override returns (uint256) {
        uint256 idle = IERC20(asset()).balanceOf(address(this));
        uint256 ownerMax = super.maxWithdraw(owner);
        return idle < ownerMax ? idle : ownerMax;
    }

    function maxRedeem(address owner) public view override returns (uint256) {
        uint256 idle = IERC20(asset()).balanceOf(address(this));
        uint256 idleShares = _convertToShares(idle, Math.Rounding.Floor);
        uint256 ownerMax = super.maxRedeem(owner);
        return idleShares < ownerMax ? idleShares : ownerMax;
    }
}
