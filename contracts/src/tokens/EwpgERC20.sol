// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "../compliance/EwpgCompliance.sol";

/// @title EwpgERC20
/// @notice Fungible security token backed by a real-world asset registered in the eWpG backend.
///
/// @dev Admin powers (all onlyRegistry):
///   - mint / burn           : standard issuance / cancellation
///   - pause / unpause       : MiCAR Art. 36, 84 — suspend all transfers
///   - freezeAddress         : AWG §17, GwG §40 — block sanctioned/AML wallets
///   - unfreezeAddress       : lift previous freeze
///   - forcedTransfer        : eWpG §24 Berichtigung — BaFin/court-ordered transfer
///   - forceBurn             : eWpG §26 Einziehung — compulsory cancellation
///   - setSupplyCap          : MiCAR Art. 46 — regulatory issuance ceiling
///
/// pause/unpause, freezeAddress, unfreezeAddress, setSupplyCap are inherited from EwpgCompliance.
/// forcedTransfer and forceBurn are implemented here because their ABI differs per token type.
contract EwpgERC20 is ERC20, Ownable, EwpgCompliance {
    /// @notice Links this contract to the off-chain registry asset record.
    bytes32 public immutable assetId;

    /// @dev Guards _update: when set to true, compliance checks are skipped for
    ///      the current call frame. Used by forcedTransfer and forceBurn so that
    ///      regulatory override operations bypass whitelist/freeze/pause.
    bool private _inForceOp;

    constructor(
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    ) ERC20(name, symbol) Ownable(registryWallet) EwpgCompliance(registryWallet) {
        assetId = _assetId;
    }

    // ── Standard issuance ─────────────────────────────────────────────────────

    /// @notice Mint tokens to a whitelisted address. Only callable by the registry wallet.
    /// @param to     Recipient address (must be whitelisted and not frozen).
    /// @param amount Number of tokens to mint in smallest unit.
    function mint(address to, uint256 amount) external onlyRegistry {
        require(isWhitelisted(to), "EwpgERC20: recipient not whitelisted");
        _requireWithinCap(totalSupply(), amount);
        _mint(to, amount);
    }

    /// @notice Burn tokens from an address. Only callable by the registry wallet.
    /// @param from   Address whose tokens will be burned.
    /// @param amount Number of tokens to burn.
    function burn(address from, uint256 amount) external onlyRegistry {
        _burn(from, amount);
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    /// @notice Transfers tokens from `from` to `to` regardless of whitelist, freeze, or pause.
    ///         To be used exclusively for BaFin / court orders (eWpG §24).
    /// @param from       Source address.
    /// @param to         Destination address.
    /// @param amount     Token amount in smallest unit.
    /// @param legalBasis Reference to legal authority (e.g. "BaFin Az. 2025-001").
    function forcedTransfer(
        address from,
        address to,
        uint256 amount,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        _transfer(from, to, amount);
        _inForceOp = false;
        emit ForcedTransfer(from, to, amount, legalBasis);
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    /// @notice Burns tokens from `from` regardless of whitelist, freeze, or pause.
    ///         To be used exclusively for BaFin compulsory cancellation (eWpG §26 Einziehung).
    /// @param from       Address whose tokens are cancelled.
    /// @param amount     Token amount in smallest unit.
    /// @param legalBasis Reference to legal authority (e.g. "BaFin Einziehungsverfügung 2025-002").
    function forceBurn(
        address from,
        uint256 amount,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        _burn(from, amount);
        _inForceOp = false;
        emit ForceBurned(from, amount, legalBasis);
    }

    // ── Transfer hook ─────────────────────────────────────────────────────────

    /// @dev Enforce whitelist, freeze, and pause on all non-mint, non-burn transfers,
    ///      unless the call is a force operation (which bypasses compliance by design).
    function _update(address from, address to, uint256 amount) internal override {
        if (!_inForceOp) {
            if (from != address(0) && to != address(0)) {
                // Normal transfer: check whitelist, freeze, pause
                _requireTransferable(from, to);
                require(isWhitelisted(to), "EwpgERC20: recipient not whitelisted");
            } else {
                // Mint or burn: still check pause and freeze on the non-zero side
                _requireTransferable(from, to);
            }
        }
        super._update(from, to, amount);
    }
}
