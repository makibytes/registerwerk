// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC20/extensions/ERC4626.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
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

    function currentNavPerShare() external view returns (uint256) {
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

    function convertToShares(uint256 assets) public view virtual override returns (uint256) {
        if (_navPerShare == 0) return assets; // 1:1 before first NAV strike
        return assets * 1e18 / _navPerShare;
    }

    function convertToAssets(uint256 shares) public view virtual override returns (uint256) {
        if (_navPerShare == 0) return shares;
        return shares * _navPerShare / 1e18;
    }

    function maxDeposit(address) public view virtual override returns (uint256) {
        if (_depositCap == 0) return type(uint256).max;
        uint256 current = totalAssets();
        return current >= _depositCap ? 0 : _depositCap - current;
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
            if (from != address(0) && to != address(0)) {
                _requireTransferable(from, to);
                require(isWhitelisted(to), "EwpgERC4626: recipient not whitelisted");
            } else {
                _requireTransferable(from, to);
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
