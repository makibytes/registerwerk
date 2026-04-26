// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "../compliance/EwpgCompliance.sol";
import "../documents/EwpgDocumentManagement.sol";

/// @title EwpgERC721
/// @notice Non-fungible security token backed by a real-world asset registered in the eWpG backend.
///         Each tokenId represents a unique asset claim (e.g. individual bond certificate).
///
/// @dev Admin powers (all onlyRegistry):
///   - mint / burn           : standard issuance / cancellation
///   - pause / unpause       : MiCAR Art. 36, 84
///   - freezeAddress         : AWG §17, GwG §40
///   - unfreezeAddress       : lift previous freeze
///   - forcedTransfer        : eWpG §24 Berichtigung — transfer by token ID
///   - forceBurn             : eWpG §26 Einziehung — burn by token ID
///   - setSupplyCap          : MiCAR Art. 46 (cap on number of minted NFTs)
///
/// For ERC-721, `value` in IEwpgAdminControls events represents the tokenId.
contract EwpgERC721 is ERC721, Ownable, EwpgCompliance, EwpgDocumentManagement {
    /// @notice Links this contract to the off-chain registry asset record.
    bytes32 public immutable assetId;

    /// @dev Total number of tokens ever minted (does not decrease on burn). Used for cap check.
    uint256 private _totalMinted;

    /// @dev When true, compliance checks are skipped for the current call frame.
    bool private _inForceOp;

    constructor(
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    ) ERC721(name, symbol) Ownable(registryWallet) EwpgCompliance(registryWallet) {
        assetId = _assetId;
    }

    // ── Standard issuance ─────────────────────────────────────────────────────

    /// @notice Mint a token to a whitelisted address. Only callable by the registry wallet.
    /// @param to      Recipient address (must be whitelisted).
    /// @param tokenId The token identifier to mint.
    function mint(address to, uint256 tokenId) external onlyRegistry {
        require(isWhitelisted(to), "EwpgERC721: recipient not whitelisted");
        _requireWithinCap(_totalMinted, 1);
        _totalMinted++;
        _mint(to, tokenId);
    }

    /// @notice Burn a token. Only callable by the registry wallet.
    /// @param tokenId The token identifier to burn.
    function burn(uint256 tokenId) external onlyRegistry {
        _burn(tokenId);
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    /// @notice Transfers `tokenId` from `from` to `to` regardless of whitelist, freeze, or pause.
    ///         Legal basis: eWpG §24 Berichtigung.
    /// @param from       Current owner.
    /// @param to         New owner.
    /// @param value      tokenId to transfer.
    /// @param legalBasis Reference to legal authority.
    function forcedTransfer(
        address from,
        address to,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        _transfer(from, to, value);
        _inForceOp = false;
        emit ForcedTransfer(from, to, value, legalBasis);
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    /// @notice Burns `tokenId` regardless of whitelist, freeze, or pause.
    ///         Legal basis: eWpG §26 Einziehung.
    /// @param from       Current owner (used for event; ownership verified by ERC-721).
    /// @param value      tokenId to burn.
    /// @param legalBasis Reference to legal authority.
    function forceBurn(
        address from,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        _burn(value);
        _inForceOp = false;
        emit ForceBurned(from, value, legalBasis);
    }

    // ── Document admin guard ──────────────────────────────────────────────────

    function _requireDocumentAdmin() internal view override {
        require(msg.sender == registry, "EwpgERC721: caller is not registry");
    }

    /// @notice Returns the term sheet URI for any tokenId (contract-level document).
    ///         Overrides the default ERC-721 tokenURI to expose the term sheet to
    ///         wallets and block explorers.
    function tokenURI(uint256) public view override returns (string memory) {
        (string memory uri,,) = this.getDocument(TERM_SHEET());
        return uri;
    }

    // ── Transfer hook ─────────────────────────────────────────────────────────

    /// @dev Enforce whitelist, freeze, and pause on all non-mint, non-burn transfers,
    ///      unless the call is a force operation.
    function _update(address to, uint256 tokenId, address auth) internal override returns (address) {
        address from = _ownerOf(tokenId);
        if (!_inForceOp) {
            if (from != address(0) && to != address(0)) {
                _requireTransferable(from, to);
                require(isWhitelisted(to), "EwpgERC721: recipient not whitelisted");
            } else {
                _requireTransferable(from, to);
            }
        }
        return super._update(to, tokenId, auth);
    }
}
