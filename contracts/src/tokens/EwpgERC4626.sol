// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC20/extensions/ERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {Math} from "@openzeppelin/contracts/utils/math/Math.sol";
import "../compliance/EwpgCompliance.sol";
import "../documents/EwpgDocumentManagement.sol";

/// @title EwpgERC4626
/// @notice Tokenized vault (EIP-4626) for eWpG-regulated money-market and bond funds.
///
/// @dev NAV-strike model:
///   Operator calls setNavPerShare periodically; convertToShares/Assets use the latest
///   NAV rather than the naïve totalAssets/totalSupply ratio.
///   Each NAV strike emits NavStruck so the off-chain registry can record the update.
///
/// @dev Admin powers (onlyRegistry):
///   - pause / unpause, freezeAddress / unfreezeAddress, whitelist — inherited from EwpgCompliance
///   - forcedTransfer, forcedApprove, forceBurn — override here with ERC-4626 share semantics
///   - setNavPerShare  — push-model NAV update (operator input from fund administrator)
///   - setDepositCap   — maximum total assets the vault accepts
contract EwpgERC4626 is ERC4626, EwpgCompliance, EwpgDocumentManagement {
    // ── Storage ───────────────────────────────────────────────────────────────

    bytes32 public immutable assetId;

    uint256 private _navPerShare;     // fixed-point 1e18; 0 = not yet struck
    uint256 private _depositCap;      // 0 = unlimited
    bool private _inForceOp;
    uint256 private _strikeCounter;

    // ── Events ────────────────────────────────────────────────────────────────

    event NavStruck(
        uint256 indexed strikeId,
        uint256 navPerShare,
        uint256 effectiveAt,
        bytes32 reportHash
    );
    event DepositCapUpdated(uint256 oldCap, uint256 newCap);

    // ── Constructor ───────────────────────────────────────────────────────────

    /// @param underlying  The ERC-20 token investors deposit (e.g. USDC).
    /// @param name        Vault share token name.
    /// @param symbol      Vault share token symbol.
    /// @param registryWallet  Registry wallet — sole admin.
    /// @param _assetId    Off-chain asset UUID (bytes32).
    constructor(
        IERC20 underlying,
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    )
        ERC4626(underlying)
        ERC20(name, symbol)
        EwpgCompliance(registryWallet)
    {
        assetId = _assetId;
    }

    // ── NAV strike ────────────────────────────────────────────────────────────

    /// @notice Operator sets the net-asset-value per share (fixed-point, 1e18 = 1.0).
    ///         Off-chain report is anchored by `reportHash` (keccak256 of the NAV attestation doc).
    function setNavPerShare(uint256 newNav, uint256 effectiveAt, bytes32 reportHash)
        external onlyRegistry
    {
        require(newNav > 0, "EwpgERC4626: NAV must be positive");
        _navPerShare = newNav;
        ++_strikeCounter;
        emit NavStruck(_strikeCounter, newNav, effectiveAt, reportHash);
    }

    function currentNavPerShare() public view returns (uint256) {
        return _navPerShare;
    }

    // ── Deposit cap ───────────────────────────────────────────────────────────

    function setDepositCap(uint256 newCap) external onlyRegistry {
        emit DepositCapUpdated(_depositCap, newCap);
        _depositCap = newCap;
    }

    function depositCap() external view returns (uint256) {
        return _depositCap;
    }

    // ── ERC-4626 NAV overrides ─────────────────────────────────────────────────
    //
    // The INTERNAL conversion hooks are overridden — not the public view pair.
    // OpenZeppelin's deposit/mint/withdraw/redeem and all preview* functions
    // route through _convertToShares/_convertToAssets; overriding only the
    // public convertTo* views would leave actual money flows on the naïve
    // totalAssets/totalSupply ratio while views report NAV — investors would
    // receive share amounts inconsistent with the struck NAV, and a donation
    // to the vault could manipulate the live ratio (classic ERC-4626
    // inflation attack). NAV-based conversion is also donation-proof, since
    // it never reads totalAssets().
    //
    // Rounding follows the EIP-4626 rule "favor the vault": OZ passes Floor
    // for deposit/redeem outputs and Ceil for mint/withdraw inputs.

    function _convertToShares(uint256 assets, Math.Rounding rounding)
        internal view virtual override returns (uint256)
    {
        if (_navPerShare == 0) return assets; // 1:1 before first NAV strike
        return Math.mulDiv(assets, 1e18, _navPerShare, rounding);
    }

    function _convertToAssets(uint256 shares, Math.Rounding rounding)
        internal view virtual override returns (uint256)
    {
        if (_navPerShare == 0) return shares;
        return Math.mulDiv(shares, _navPerShare, 1e18, rounding);
    }

    function maxDeposit(address) public view virtual override returns (uint256) {
        if (_depositCap == 0) return type(uint256).max;
        uint256 current = totalAssets();
        return current >= _depositCap ? 0 : _depositCap - current;
    }

    /// @dev Without this override the deposit cap is bypassable: OZ's mint()
    ///      checks maxMint (default: unlimited), not maxDeposit.
    function maxMint(address receiver) public view virtual override returns (uint256) {
        uint256 maxAssets = maxDeposit(receiver);
        if (maxAssets == type(uint256).max) return type(uint256).max;
        return _convertToShares(maxAssets, Math.Rounding.Floor);
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    function forcedTransfer(
        address from,
        address to,
        uint256 value,
        string calldata legalBasis
    ) external onlyRegistry {
        _inForceOp = true;
        _transfer(from, to, value);
        _inForceOp = false;
        emit ForcedTransfer(from, to, value, legalBasis);
    }

    function forcedApprove(
        address owner,
        address spender,
        uint256 value,
        string calldata legalBasis
    ) external onlyRegistry {
        _approve(owner, spender, value);
        emit ForcedApprove(owner, spender, value, legalBasis);
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    function forceBurn(
        address from,
        uint256 value,
        string calldata legalBasis
    ) external onlyRegistry {
        _inForceOp = true;
        _burn(from, value);
        _inForceOp = false;
        emit ForceBurned(from, value, legalBasis);
    }

    // ── Compliance transfer hook ──────────────────────────────────────────────

    function _update(address from, address to, uint256 value) internal virtual override {
        if (!_inForceOp) {
            _requireTransferable(from, to);
            // Whitelist every share acquisition — including the mint leg of
            // deposit()/mint(). ERC-4626 deposits are permissionless by default,
            // so without this check any address could become a shareholder of
            // the regulated fund by depositing underlying. The vault's own
            // address is exempt: EwpgERC7540 locks shares in self-custody
            // during the async redemption window.
            if (to != address(0) && to != address(this)) {
                require(isWhitelisted(to), "EwpgERC4626: recipient not whitelisted");
            }
        }
        super._update(from, to, value);
    }

    // ── Document admin guard ──────────────────────────────────────────────────

    function _requireDocumentAdmin() internal view override {
        require(msg.sender == registry, "EwpgERC4626: caller is not registry");
    }

    // ── View ──────────────────────────────────────────────────────────────────

    function strikeCounter() external view returns (uint256) {
        return _strikeCounter;
    }
}
