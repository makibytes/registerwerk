// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/extensions/ERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuardTransient.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";
import "./EwpgRepoMarket.sol";
import "./EwpgRepoMarketFactory.sol";

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
/// @dev Curator model: the vault is bound to one curator org ({curatorOrg}); a wallet of that
///      org holding `repo-markets.curate-vault` adds/removes markets and caps, and moves idle
///      cash into/out of a market via {allocate}/{deallocate}. A same-slug grant held by any
///      other org cannot administer this instance. Only markets deployed by {factory}
///      ({EwpgRepoMarketFactory-isMarket}) can be added, and every change that raises the
///      vault's exposure — adding a market, raising a cap — is two-step: `submit*` records it,
///      `accept*` applies it no earlier than {timelock} later. The curator or the operator org
///      ({operatorOrg}, holding `repo-markets.configure`) can {revokePending} in between.
///      Cap decreases and {removeMarket} reduce exposure and stay immediate. Withdrawals are served from idle cash only —
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
///      refinement and is not implemented here.
contract EwpgRepoVault is ERC4626, RegisterwerkGated, ReentrancyGuardTransient {
    using SafeERC20 for IERC20;

    bytes32 public constant CURATE = keccak256("repo-markets.curate-vault");

    struct MarketAllocation {
        bool enabled;
        uint256 capWad; // max the vault will ever allocate into this market, in loan-token units
    }

    bytes32 public constant CONFIGURE = keccak256("repo-markets.configure");

    /// @notice Minimum {timelock} outside the local development chain.
    uint256 public constant MIN_TIMELOCK = 1 days;
    /// @notice Anvil / Foundry default chain id — the only chain allowed a shorter timelock.
    uint256 private constant LOCAL_DEV_CHAIN_ID = 31337;

    /// @notice The only factory whose markets this vault may allocate into.
    EwpgRepoMarketFactory public immutable factory;
    /// @notice The org whose wallets curate this instance (see {RegisterwerkGated-requiresOrgPermission}).
    address public immutable curatorOrg;
    /// @notice The operating org, which may {revokePending} a curator change.
    address public immutable operatorOrg;
    /// @notice Delay between `submit*` and `accept*` for exposure-raising changes.
    uint256 public immutable timelock;

    struct PendingCap {
        uint256 capWad;
        uint64 validAt; // 0 = nothing pending
    }

    /// @notice Every market ever added (including later-disabled ones — see {marketCount}).
    EwpgRepoMarket[] public marketList;
    mapping(address => MarketAllocation) public allocations;
    mapping(address => bool) private _marketEverAdded;
    /// @notice Pending market addition (market not enabled) or cap increase (market enabled).
    mapping(address => PendingCap) public pendingCap;

    event MarketAdded(address indexed market, uint256 capWad);
    event MarketCapUpdated(address indexed market, uint256 capWad);
    event MarketRemoved(address indexed market);
    event Allocated(address indexed market, uint256 amount);
    event Deallocated(address indexed market, uint256 amount);
    event MarketAddSubmitted(address indexed market, uint256 capWad, uint256 validAt);
    event CapIncreaseSubmitted(address indexed market, uint256 capWad, uint256 validAt);
    /// @notice A pending addition or cap increase was withdrawn before acceptance.
    event PendingRevoked(address indexed market, address indexed by);

    error MarketNotEnabled(address market);
    error MarketAlreadyAdded(address market);
    error ExceedsMarketCap(address market);
    error LoanTokenMismatch(address market);
    error ZeroAddress();
    error NotFactoryMarket(address market);
    error TimelockTooShort(uint256 timelock);
    error AlreadyPending(address market);
    error NoPendingChange(address market);
    error TimelockNotElapsed(address market, uint256 validAt);
    /// @notice {setMarketCap} only lowers a cap; raising one goes through {submitCapIncrease}.
    error CapIncreaseRequiresTimelock(address market);

    /// @param factory_ The {EwpgRepoMarketFactory} whose markets are allowed.
    /// @param curatorOrg_ The org curating this vault.
    /// @param operatorOrg_ The operating org, allowed to {revokePending} (may equal `curatorOrg_`).
    /// @param timelock_ At least {MIN_TIMELOCK}, except on the local dev chain (31337).
    constructor(
        IPermissionOracle oracle_,
        IERC20 loanToken_,
        EwpgRepoMarketFactory factory_,
        address curatorOrg_,
        address operatorOrg_,
        uint256 timelock_,
        string memory name_,
        string memory symbol_
    ) ERC4626(loanToken_) ERC20(name_, symbol_) RegisterwerkGated(oracle_) {
        if (address(factory_) == address(0)) revert ZeroAddress();
        _requireOrg(curatorOrg_);
        _requireOrg(operatorOrg_);
        if (timelock_ < MIN_TIMELOCK && block.chainid != LOCAL_DEV_CHAIN_ID) revert TimelockTooShort(timelock_);
        factory = factory_;
        curatorOrg = curatorOrg_;
        operatorOrg = operatorOrg_;
        timelock = timelock_;
    }

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

    /// @notice Proposes a new market this vault may allocate into, with its allocation cap.
    ///         Reverts unless `market` was deployed by {factory} and lends this vault's
    ///         underlying asset. Takes effect via {acceptAddMarket} after {timelock}.
    function submitAddMarket(EwpgRepoMarket market, uint256 capWad)
        external
        requiresOrgPermission(curatorOrg, CURATE)
    {
        if (address(market) == address(0)) revert ZeroAddress();
        if (!factory.isMarket(address(market))) revert NotFactoryMarket(address(market));
        if (address(market.loanToken()) != asset()) revert LoanTokenMismatch(address(market));
        if (allocations[address(market)].enabled) revert MarketAlreadyAdded(address(market));
        uint256 validAt = _submit(market, capWad);
        emit MarketAddSubmitted(address(market), capWad, validAt);
    }

    /// @notice Applies a pending {submitAddMarket} once its timelock has elapsed.
    function acceptAddMarket(EwpgRepoMarket market) external requiresOrgPermission(curatorOrg, CURATE) {
        if (allocations[address(market)].enabled) revert MarketAlreadyAdded(address(market));
        uint256 capWad = _accept(market);
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

    /// @notice Proposes raising an enabled market's cap. Takes effect via
    ///         {acceptCapIncrease} after {timelock}.
    function submitCapIncrease(EwpgRepoMarket market, uint256 capWad)
        external
        requiresOrgPermission(curatorOrg, CURATE)
    {
        MarketAllocation storage alloc = allocations[address(market)];
        if (!alloc.enabled) revert MarketNotEnabled(address(market));
        if (capWad <= alloc.capWad) revert CapIncreaseRequiresTimelock(address(market));
        uint256 validAt = _submit(market, capWad);
        emit CapIncreaseSubmitted(address(market), capWad, validAt);
    }

    /// @notice Applies a pending {submitCapIncrease} once its timelock has elapsed.
    function acceptCapIncrease(EwpgRepoMarket market) external requiresOrgPermission(curatorOrg, CURATE) {
        if (!allocations[address(market)].enabled) revert MarketNotEnabled(address(market));
        uint256 capWad = _accept(market);
        allocations[address(market)].capWad = capWad;
        emit MarketCapUpdated(address(market), capWad);
    }

    /// @notice Withdraws a pending addition or cap increase. Callable by the curator org
    ///         (`repo-markets.curate-vault`) or the operator org (`repo-markets.configure`).
    function revokePending(EwpgRepoMarket market) external {
        bool isCurator = oracle.orgOf(msg.sender) == curatorOrg && oracle.hasPermission(msg.sender, CURATE);
        if (!isCurator) _checkOrgPermission(msg.sender, operatorOrg, CONFIGURE);
        if (pendingCap[address(market)].validAt == 0) revert NoPendingChange(address(market));
        delete pendingCap[address(market)];
        emit PendingRevoked(address(market), msg.sender);
    }

    /// @notice Lowers the allocation cap of an already-added market, effective immediately.
    ///         Also drops any pending cap increase for it. Raising a cap goes through
    ///         {submitCapIncrease}.
    function setMarketCap(EwpgRepoMarket market, uint256 capWad) external requiresOrgPermission(curatorOrg, CURATE) {
        if (!allocations[address(market)].enabled) revert MarketNotEnabled(address(market));
        if (capWad > allocations[address(market)].capWad) revert CapIncreaseRequiresTimelock(address(market));
        allocations[address(market)].capWad = capWad;
        delete pendingCap[address(market)];
        emit MarketCapUpdated(address(market), capWad);
    }

    function _submit(EwpgRepoMarket market, uint256 capWad) private returns (uint256 validAt) {
        if (pendingCap[address(market)].validAt != 0) revert AlreadyPending(address(market));
        validAt = block.timestamp + timelock;
        pendingCap[address(market)] = PendingCap(capWad, uint64(validAt));
    }

    function _accept(EwpgRepoMarket market) private returns (uint256 capWad) {
        PendingCap memory p = pendingCap[address(market)];
        if (p.validAt == 0) revert NoPendingChange(address(market));
        if (block.timestamp < p.validAt) revert TimelockNotElapsed(address(market), p.validAt);
        delete pendingCap[address(market)];
        return p.capWad;
    }

    /// @notice Disables further allocation into `market`. Does not forcibly withdraw existing
    ///         principal — call {deallocate} first to unwind an existing position.
    function removeMarket(EwpgRepoMarket market) external requiresOrgPermission(curatorOrg, CURATE) {
        if (!allocations[address(market)].enabled) revert MarketNotEnabled(address(market));
        allocations[address(market)].enabled = false;
        delete pendingCap[address(market)];
        emit MarketRemoved(address(market));
    }

    /// @notice Moves `amount` of idle vault cash into `market`'s lender side, up to that
    ///         market's configured cap.
    function allocate(EwpgRepoMarket market, uint256 amount) external nonReentrant requiresOrgPermission(curatorOrg, CURATE) {
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
    function deallocate(EwpgRepoMarket market, uint256 amount) external nonReentrant requiresOrgPermission(curatorOrg, CURATE) {
        if (
            !_marketEverAdded[address(market)]
                || (!allocations[address(market)].enabled && market.balanceOf(address(this)) == 0)
        ) {
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
