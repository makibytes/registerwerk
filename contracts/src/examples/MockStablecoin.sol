// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/token/ERC20/extensions/ERC20Permit.sol";

/// @title MockStablecoin
/// @notice Demo/test payment-token stand-in. It does not establish MiCAR classification or
///         model an authorised issuer, safeguarding, or redemption. On real networks the payment-rail catalog
///         points at the issuer's actual token contract; this mock exists only so the
///         example dApps, tests, and demo deployments have a payment leg to move.
///         Implements EIP-2612 `permit` — USDC supports this natively; AllUnity Euro's
///         real support should be verified before relying on `subscribeWithPermit`-style
///         flows against it in production (see `docs/platform/account-abstraction.md`).
/// @dev Open `mint` — never deploy to a production chain.
contract MockStablecoin is ERC20, ERC20Permit {
    uint8 private immutable _decimals;

    constructor(string memory name_, string memory symbol_, uint8 decimals_)
        ERC20(name_, symbol_)
        ERC20Permit(name_)
    {
        _decimals = decimals_;
    }

    function decimals() public view override returns (uint8) {
        return _decimals;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }
}
