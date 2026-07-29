// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "../ecosystem/RegisterwerkGated.sol";
import "../ecosystem/interfaces/IPermissionOracle.sol";

/// @title StablecoinAmm
/// @notice Reference marketplace dApp: a minimal constant-product (x*y=k) automated market
///         maker for a single pair of MiCAR-regulated e-money-token stablecoins (e.g.
///         AUEUR/USDC) declared through the payment-rail catalog
///         (`PaymentRailType.STABLECOIN`). Restricted to stablecoin-only pairs: neither leg
///         is a security, so there is no securities-law-driven investor-eligibility
///         question for holding or swapping stablecoins as such — but it still inherits
///         `RegisterwerkGated` like every other example dApp, gating on org membership and
///         permission rather than a claim topic, for framework consistency regardless of
///         that legal conclusion (whether AMM-style stablecoin swapping is itself a
///         MiCAR Title V CASP-regulated activity is a separate, unresolved question the
///         deploying org is responsible for). See `docs/platform/defi-interoperability.md`.
/// @dev Unaudited reference implementation of a minimal, self-contained Uniswap-v2-style
///      constant-product pool with LP shares tracked as this contract's own ERC-20. First
///      deposit sets the pool ratio and permanently locks `MINIMUM_LIQUIDITY` shares
///      (minted to a burn address) to guard against a first-depositor share-price
///      manipulation attack.
contract StablecoinAmm is ERC20, ReentrancyGuard, RegisterwerkGated {
    using SafeERC20 for IERC20;

    bytes32 public constant PROVIDE_LIQUIDITY = keccak256("stablecoin-amm.provide-liquidity");
    bytes32 public constant SWAP = keccak256("stablecoin-amm.swap");

    uint256 public constant MINIMUM_LIQUIDITY = 1e3;
    uint256 public constant FEE_BPS = 30; // 0.30%, Uniswap-v2-style
    uint256 private constant BPS_DENOMINATOR = 10_000;
    address private constant BURN_ADDRESS = address(0xdead);

    IERC20 public immutable tokenA;
    IERC20 public immutable tokenB;

    uint256 public reserveA;
    uint256 public reserveB;

    event LiquidityAdded(address indexed provider, uint256 amountA, uint256 amountB, uint256 shares);
    event LiquidityRemoved(address indexed provider, uint256 amountA, uint256 amountB, uint256 shares);
    event Swapped(address indexed trader, address indexed tokenIn, uint256 amountIn, uint256 amountOut);

    error ZeroAddress();
    error IdenticalTokens();
    error ZeroAmount();
    error InsufficientLiquidityMinted();
    error InsufficientLiquidityBurned();
    error InsufficientOutputAmount();
    error InvalidToken();
    error SlippageExceeded();

    constructor(IPermissionOracle oracle_, IERC20 tokenA_, IERC20 tokenB_, string memory name_, string memory symbol_)
        ERC20(name_, symbol_)
        RegisterwerkGated(oracle_)
    {
        if (address(tokenA_) == address(0) || address(tokenB_) == address(0)) revert ZeroAddress();
        if (address(tokenA_) == address(tokenB_)) revert IdenticalTokens();
        tokenA = tokenA_;
        tokenB = tokenB_;
    }

    /// @notice Deposits tokens at the current pool ratio (or sets the initial ratio on the
    ///         first deposit) and mints LP shares proportional to the pool's growth.
    function addLiquidity(uint256 amountA, uint256 amountB, uint256 minShares)
        external
        nonReentrant
        requiresPermission(PROVIDE_LIQUIDITY)
        returns (uint256 shares)
    {
        if (amountA == 0 || amountB == 0) revert ZeroAmount();

        uint256 totalShares = totalSupply();
        if (totalShares == 0) {
            shares = _sqrt(amountA * amountB);
            if (shares <= MINIMUM_LIQUIDITY) revert InsufficientLiquidityMinted();
            shares -= MINIMUM_LIQUIDITY;
            _mint(BURN_ADDRESS, MINIMUM_LIQUIDITY); // permanently locked, never withdrawable
        } else {
            shares = _min((amountA * totalShares) / reserveA, (amountB * totalShares) / reserveB);
        }
        if (shares == 0 || shares < minShares) revert InsufficientLiquidityMinted();

        tokenA.safeTransferFrom(msg.sender, address(this), amountA);
        tokenB.safeTransferFrom(msg.sender, address(this), amountB);
        reserveA += amountA;
        reserveB += amountB;
        _mint(msg.sender, shares);

        emit LiquidityAdded(msg.sender, amountA, amountB, shares);
    }

    /// @notice Burns `shares` LP tokens and returns the proportional share of both reserves.
    function removeLiquidity(uint256 shares, uint256 minAmountA, uint256 minAmountB)
        external
        nonReentrant
        requiresPermission(PROVIDE_LIQUIDITY)
        returns (uint256 amountA, uint256 amountB)
    {
        if (shares == 0) revert ZeroAmount();
        uint256 totalShares = totalSupply();
        amountA = (shares * reserveA) / totalShares;
        amountB = (shares * reserveB) / totalShares;
        if (amountA == 0 || amountB == 0) revert InsufficientLiquidityBurned();
        if (amountA < minAmountA || amountB < minAmountB) revert SlippageExceeded();

        _burn(msg.sender, shares);
        reserveA -= amountA;
        reserveB -= amountB;
        tokenA.safeTransfer(msg.sender, amountA);
        tokenB.safeTransfer(msg.sender, amountB);

        emit LiquidityRemoved(msg.sender, amountA, amountB, shares);
    }

    /// @notice Swaps an exact input amount of `tokenIn` (must be {tokenA} or {tokenB}) for
    ///         the other token, applying a 0.30% fee, and reverts if the output would fall
    ///         below `minAmountOut`.
    function swapExactIn(IERC20 tokenIn, uint256 amountIn, uint256 minAmountOut)
        external
        nonReentrant
        requiresPermission(SWAP)
        returns (uint256 amountOut)
    {
        if (amountIn == 0) revert ZeroAmount();
        bool inIsA = address(tokenIn) == address(tokenA);
        if (!inIsA && address(tokenIn) != address(tokenB)) revert InvalidToken();

        (uint256 reserveIn, uint256 reserveOut, IERC20 tokenOut) =
            inIsA ? (reserveA, reserveB, tokenB) : (reserveB, reserveA, tokenA);

        uint256 amountInWithFee = amountIn * (BPS_DENOMINATOR - FEE_BPS);
        amountOut = (amountInWithFee * reserveOut) / (reserveIn * BPS_DENOMINATOR + amountInWithFee);
        if (amountOut == 0 || amountOut < minAmountOut) revert InsufficientOutputAmount();

        tokenIn.safeTransferFrom(msg.sender, address(this), amountIn);
        tokenOut.safeTransfer(msg.sender, amountOut);

        if (inIsA) {
            reserveA += amountIn;
            reserveB -= amountOut;
        } else {
            reserveB += amountIn;
            reserveA -= amountOut;
        }

        emit Swapped(msg.sender, address(tokenIn), amountIn, amountOut);
    }

    function _min(uint256 a, uint256 b) private pure returns (uint256) {
        return a < b ? a : b;
    }

    function _sqrt(uint256 y) private pure returns (uint256 z) {
        if (y > 3) {
            z = y;
            uint256 x = y / 2 + 1;
            while (x < z) {
                z = x;
                x = (y / x + x) / 2;
            }
        } else if (y != 0) {
            z = 1;
        }
    }
}
