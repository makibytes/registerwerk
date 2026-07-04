// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "../compliance/EwpgCompliance.sol";
import "../documents/EwpgDocumentManagement.sol";

/// @title EwpgERC1155
/// @notice Multi-token security token backed by real-world assets registered in the eWpG backend.
///         Each token id represents a distinct asset class or tranche.
///
/// @dev Admin powers (all onlyRegistry):
///   - mint / mintBatch / burn : standard issuance / cancellation
///   - pause / unpause         : MiCAR Art. 36, 84
///   - freezeAddress           : AWG §17, GwG §40
///   - unfreezeAddress         : lift previous freeze
///   - forcedTransfer          : eWpG §24 Berichtigung (single id)
///   - forcedTransferBatch     : eWpG §24 Berichtigung (multiple ids at once)
///   - forceBurn               : eWpG §26 Einziehung (single id)
///   - setSupplyCap            : MiCAR Art. 46 (per-contract aggregate cap)
///
/// NOTE: IEwpgAdminControls.forcedTransfer / forceBurn operate on a single id/amount pair.
///       For multi-id operations use forcedTransferBatch / forceBurnBatch.
contract EwpgERC1155 is ERC1155, Ownable, EwpgCompliance, EwpgDocumentManagement, ReentrancyGuard {
    /// @notice Links this contract to the off-chain registry asset record.
    bytes32 public immutable assetId;

    /// @notice Human-readable symbol used for display purposes (ERC-1155 has no symbol()).
    string public symbol;

    /// @dev Aggregate total minted across all ids (for supply cap).
    uint256 private _totalMinted;

    /// @dev When true, compliance checks are skipped for the current call frame.
    bool private _inForceOp;

    constructor(
        string memory _symbol,
        address registryWallet,
        bytes32 _assetId
    ) ERC1155("") Ownable(registryWallet) EwpgCompliance(registryWallet) {
        symbol = _symbol;
        assetId = _assetId;
    }

    // ── Standard issuance ─────────────────────────────────────────────────────

    /// @notice Mint tokens of a single id to a whitelisted address.
    function mint(
        address to,
        uint256 id,
        uint256 amount,
        bytes calldata data
    ) external onlyRegistry nonReentrant {
        require(isWhitelisted(to), "EwpgERC1155: recipient not whitelisted");
        _requireWithinCap(_totalMinted, amount);
        _totalMinted += amount;
        _mint(to, id, amount, data);
    }

    /// @notice Batch-mint multiple token ids to a whitelisted address.
    function mintBatch(
        address to,
        uint256[] calldata ids,
        uint256[] calldata amounts,
        bytes calldata data
    ) external onlyRegistry nonReentrant {
        require(isWhitelisted(to), "EwpgERC1155: recipient not whitelisted");
        uint256 total;
        for (uint256 i; i < amounts.length; i++) total += amounts[i];
        _requireWithinCap(_totalMinted, total);
        _totalMinted += total;
        _mintBatch(to, ids, amounts, data);
    }

    /// @notice Burn tokens of a single id from an address.
    function burn(address from, uint256 id, uint256 amount) external onlyRegistry {
        _burn(from, id, amount);
    }

    // ── Forced transfer — eWpG §24 Berichtigung (single id) ──────────────────

    /// @notice Transfers `value` units of token `id` from `from` to `to`, bypassing all
    ///         compliance checks. Legal basis: eWpG §24 Berichtigung.
    ///
    /// @dev IEwpgAdminControls.forcedTransfer encodes id in the high 128 bits of `value`
    ///      and amount in the low 128 bits when called generically; but the explicit
    ///      forcedTransferSingle below is preferred for on-chain use.
    ///
    ///      Balances are moved through the internal {ERC1155-_update} rather than
    ///      {_safeTransferFrom}. The safe path invokes {IERC1155Receiver-onERC1155Received}
    ///      on `to` while {_inForceOp} is still true, which would let a malicious recipient
    ///      re-enter and move frozen / non-whitelisted tokens with all compliance checks
    ///      disabled. Going through _update performs no external call, closing that window;
    ///      nonReentrant is kept as defence-in-depth. A court-ordered §24 correction must
    ///      also not be defeatable by a recipient contract that reverts in its receiver hook.
    function forcedTransfer(
        address from,
        address to,
        uint256 value,     // interpreted as amount; id defaults to 0 for ABI compat
        string calldata legalBasis
    ) external override onlyRegistry nonReentrant {
        _forceMove(from, to, 0, value);
        emit ForcedTransfer(from, to, value, legalBasis);
    }

    /// @notice Preferred overload: transfer `amount` of token `id` between addresses.
    function forcedTransferSingle(
        address from,
        address to,
        uint256 id,
        uint256 amount,
        string calldata legalBasis
    ) external onlyRegistry nonReentrant {
        _forceMove(from, to, id, amount);
        emit ForcedTransfer(from, to, amount, legalBasis);
    }

    /// @notice Batch forced transfer (multiple ids in one transaction).
    function forcedTransferBatch(
        address from,
        address to,
        uint256[] calldata ids,
        uint256[] calldata amounts,
        string calldata legalBasis
    ) external onlyRegistry nonReentrant {
        _inForceOp = true;
        _update(from, to, ids, amounts);
        _inForceOp = false;
        for (uint256 i; i < ids.length; i++) {
            emit ForcedTransfer(from, to, amounts[i], legalBasis);
        }
    }

    /// @dev Moves a single (id, amount) between two non-zero addresses without invoking
    ///      the ERC-1155 acceptance callback. Used only by the forced-operation paths.
    function _forceMove(address from, address to, uint256 id, uint256 amount) private {
        uint256[] memory ids = new uint256[](1);
        uint256[] memory amounts = new uint256[](1);
        ids[0] = id;
        amounts[0] = amount;
        _inForceOp = true;
        _update(from, to, ids, amounts);
        _inForceOp = false;
    }

    // ── Forced approve — regulatory override ──────────────────────────────────

    /// @notice Sets operator approval for `operator` over all of `owner`'s tokens,
    ///         bypassing owner consent. ERC-1155 has no per-token allowances; this
    ///         grants or revokes full operator status.
    ///         value > 0 = grant; value == 0 = revoke.
    /// @param owner      Token owner whose operator approval is being overridden.
    /// @param operator   Address receiving operator approval.
    /// @param value      > 0 = approve; 0 = revoke.
    /// @param legalBasis Reference to legal authority (e.g. "BaFin Az. 2025-002").
    function forcedApprove(
        address owner,
        address operator,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _setApprovalForAll(owner, operator, value > 0);
        emit ForcedApprove(owner, operator, value, legalBasis);
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    /// @notice Burns `value` units (of id 0) from `from`, bypassing all compliance checks.
    ///         Use forceBurnSingle for a specific id.
    function forceBurn(
        address from,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        _burn(from, 0, value);
        _inForceOp = false;
        emit ForceBurned(from, value, legalBasis);
    }

    /// @notice Burns `amount` of token `id` from `from`, bypassing all compliance checks.
    function forceBurnSingle(
        address from,
        uint256 id,
        uint256 amount,
        string calldata legalBasis
    ) external onlyRegistry {
        _inForceOp = true;
        _burn(from, id, amount);
        _inForceOp = false;
        emit ForceBurned(from, amount, legalBasis);
    }

    // ── Document admin guard ──────────────────────────────────────────────────

    function _requireDocumentAdmin() internal view override {
        require(msg.sender == registry, "EwpgERC1155: caller is not registry");
    }

    /// @notice Returns the term sheet URI for any token id (contract-level document).
    ///         Overrides the default ERC-1155 uri() to expose the term sheet to
    ///         wallets and block explorers.
    function uri(uint256) public view override returns (string memory) {
        (string memory _uri,,) = this.getDocument(TERM_SHEET());
        return _uri;
    }

    // ── Transfer hook ─────────────────────────────────────────────────────────

    /// @dev Enforce whitelist, freeze, and pause on all non-mint, non-burn transfers,
    ///      unless the call is a force operation.
    function _update(
        address from,
        address to,
        uint256[] memory ids,
        uint256[] memory values
    ) internal override {
        if (!_inForceOp) {
            if (from != address(0) && to != address(0)) {
                _requireTransferable(from, to);
                require(isWhitelisted(to), "EwpgERC1155: recipient not whitelisted");
            } else {
                _requireTransferable(from, to);
            }
        }
        super._update(from, to, ids, values);
    }
}
