// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "../standards/erc3525/ERC3525.sol";
import "../compliance/EwpgCompliance.sol";

/// @title EwpgERC3525
/// @notice Semi-fungible security token (EIP-3525) for eWpG-regulated bonds and fund shares.
///
/// @dev Architecture:
///   - Each bond series or fund tranche maps to a *slot*.
///   - Each investor holding maps to a *token* with a *value* (notional in smallest unit).
///   - Slots carry off-chain metadata (coupon rate, ISIN sub-series, maturity) anchored
///     on-chain by a keccak256 hash via setSlotMetadataHash.
///   - Admin powers (all onlyRegistry) include both slot-level and token-level controls:
///
///   Slot-level:
///     pauseSlot / unpauseSlot  — suspend all value transfers in a series (e.g. pending restructuring)
///     setSlotSupplyCap         — cap total value mintable in a series
///     setSlotMetadataHash      — anchor off-chain coupon/maturity schedule hash on-chain
///
///   Token-level:
///     freezeToken / unfreezeToken    — block a specific holding (AWG §17 sanctions)
///     forcedTransferValue            — eWpG §24 Berichtigung (court order)
///     forceBurnValue                 — eWpG §26 Einziehung (compulsory cancellation)
///
///   Global (inherited from EwpgCompliance):
///     pause / unpause, freezeAddress / unfreezeAddress, setSupplyCap
contract EwpgERC3525 is ERC3525, EwpgCompliance {
    // ── Storage ───────────────────────────────────────────────────────────────

    bytes32 public immutable assetId;

    struct SlotConfig {
        uint256 supplyCap;    // 0 = unlimited
        uint256 totalMinted;
        bool paused;
        bytes32 metadataHash; // keccak256 of off-chain JSON; 0 = not set
    }

    mapping(uint256 => SlotConfig) private _slots;
    mapping(uint256 => bool) private _frozenTokens; // tokenId → frozen
    bool private _inForceOp;

    // ── Events ────────────────────────────────────────────────────────────────

    event SlotPaused(uint256 indexed slot, address indexed by);
    event SlotUnpaused(uint256 indexed slot, address indexed by);
    event SlotSupplyCapSet(uint256 indexed slot, uint256 cap);
    event SlotMetadataHashSet(uint256 indexed slot, bytes32 metadataHash);
    event TokenFrozen(uint256 indexed tokenId, string reason);
    event TokenUnfrozen(uint256 indexed tokenId);
    event ForcedValueTransfer(
        uint256 indexed fromTokenId, uint256 indexed toTokenId, uint256 value, string legalBasis);
    event ForcedValueBurn(uint256 indexed tokenId, uint256 value, string legalBasis);

    // ── Constructor ───────────────────────────────────────────────────────────

    constructor(
        string memory name,
        string memory symbol,
        address registryWallet,
        bytes32 _assetId
    ) ERC3525(name, symbol) EwpgCompliance(registryWallet) {
        assetId = _assetId;
    }

    // ── Issuance ──────────────────────────────────────────────────────────────

    /// @notice Mint a new token in `slot` with `value` units to `to`.
    function mint(address to, uint256 slot, uint256 value)
        external onlyRegistry
        returns (uint256 tokenId)
    {
        require(!isPaused(), "EwpgERC3525: global transfers are paused");
        require(!isFrozen(to), "EwpgERC3525: destination address is frozen");
        require(!_slots[slot].paused, "EwpgERC3525: slot is paused");
        require(isWhitelisted(to), "EwpgERC3525: recipient not whitelisted");
        SlotConfig storage cfg = _slots[slot];
        if (cfg.supplyCap > 0) {
            require(cfg.totalMinted + value <= cfg.supplyCap,
                    "EwpgERC3525: slot supply cap exceeded");
        }
        cfg.totalMinted += value;
        tokenId = _mintToken(to, slot, value);
    }

    /// @notice Burn all value from a token and delete it.
    function burn(uint256 tokenId, string calldata reason) external onlyRegistry {
        uint256 remaining = balanceOf(tokenId);
        if (remaining > 0) {
            _transferValue(tokenId, tokenId, 0); // enforce pause/freeze guards via the override hook
            _burnValue(tokenId, remaining);
        }
        _burnToken(tokenId);
        emit ForcedValueBurn(tokenId, remaining, reason);
    }

    // ── Slot-level admin ──────────────────────────────────────────────────────

    function pauseSlot(uint256 slot) external onlyRegistry {
        require(!_slots[slot].paused, "EwpgERC3525: already paused");
        _slots[slot].paused = true;
        emit SlotPaused(slot, msg.sender);
    }

    function unpauseSlot(uint256 slot) external onlyRegistry {
        require(_slots[slot].paused, "EwpgERC3525: not paused");
        _slots[slot].paused = false;
        emit SlotUnpaused(slot, msg.sender);
    }

    function setSlotSupplyCap(uint256 slot, uint256 cap) external onlyRegistry {
        _slots[slot].supplyCap = cap;
        emit SlotSupplyCapSet(slot, cap);
    }

    function setSlotMetadataHash(uint256 slot, bytes32 metadataHash) external onlyRegistry {
        _slots[slot].metadataHash = metadataHash;
        emit SlotMetadataHashSet(slot, metadataHash);
    }

    function slotConfig(uint256 slot)
        external view
        returns (uint256 supplyCap, uint256 totalMinted, bool paused, bytes32 metadataHash)
    {
        SlotConfig storage cfg = _slots[slot];
        return (cfg.supplyCap, cfg.totalMinted, cfg.paused, cfg.metadataHash);
    }

    // ── Token-level admin ─────────────────────────────────────────────────────

    function freezeToken(uint256 tokenId, string calldata reason) external onlyRegistry {
        _requireOwned(tokenId);
        _frozenTokens[tokenId] = true;
        emit TokenFrozen(tokenId, reason);
    }

    function unfreezeToken(uint256 tokenId) external onlyRegistry {
        _requireOwned(tokenId);
        _frozenTokens[tokenId] = false;
        emit TokenUnfrozen(tokenId);
    }

    function isTokenFrozen(uint256 tokenId) public view returns (bool) {
        return _frozenTokens[tokenId];
    }

    // ── Forced operations — eWpG §24 (transfer) & §26 (Einziehung) ───────────

    function forcedTransferValue(
        uint256 fromTokenId,
        uint256 toTokenId,
        uint256 value,
        string calldata legalBasis
    ) external onlyRegistry {
        require(slotOf(fromTokenId) == slotOf(toTokenId),
                "EwpgERC3525: tokens in different slots");
        _inForceOp = true;
        _transferValue(fromTokenId, toTokenId, value);
        _inForceOp = false;
        emit ForcedValueTransfer(fromTokenId, toTokenId, value, legalBasis);
    }

    function forceBurnValue(
        uint256 tokenId,
        uint256 value,
        string calldata legalBasis
    ) external onlyRegistry {
        _inForceOp = true;
        require(balanceOf(tokenId) >= value, "EwpgERC3525: insufficient balance");
        _burnValue(tokenId, value);
        _inForceOp = false;
        emit ForcedValueBurn(tokenId, value, legalBasis);
    }

    // ── IEwpgAdminControls — whole-token (NFT) admin ops ─────────────────────
    // ERC-3525 tokens are ERC-721-based (one owner per tokenId), so — matching the
    // EwpgERC721 convention — `value` here is the tokenId itself, and these functions
    // operate on token *ownership*. This is distinct from forcedTransferValue /
    // forceBurnValue above, which move partial *value* between two existing tokens
    // of the same slot without changing ownership.

    /// @notice Transfers the NFT with id `value` (and the balance it carries) from
    ///         `from` to `to`, bypassing whitelist/freeze/pause. Legal basis: eWpG §24.
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

    /// @notice Approves `spender` for the NFT with id `value` on behalf of `owner`,
    ///         bypassing owner consent.
    function forcedApprove(
        address owner,
        address spender,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _approve(spender, value, address(0));
        emit ForcedApprove(owner, spender, value, legalBasis);
    }

    /// @notice Fully cancels the token with id `value`: burns all remaining balance and
    ///         deletes the NFT itself. Legal basis: eWpG §26 Einziehung. Unlike
    ///         forceBurnValue (which reduces a token's balance while keeping it alive),
    ///         this removes the position entirely.
    function forceBurn(
        address from,
        uint256 value,
        string calldata legalBasis
    ) external override onlyRegistry {
        _inForceOp = true;
        uint256 remaining = balanceOf(value);
        if (remaining > 0) {
            _burnValue(value, remaining);
        }
        _burnToken(value);
        _inForceOp = false;
        emit ForceBurned(from, value, legalBasis);
    }

    // ── Compliance hooks ──────────────────────────────────────────────────────

    /// @dev Address-form value transfers create a new ERC-721 destination token before moving
    ///      value into it. Check the recipient before that mint so an unwhitelisted address can
    ///      never acquire a position; a revert also rolls the token-id counter back atomically.
    ///      The zero address is left to ERC-721's normal invalid-receiver handling.
    function transferFrom(uint256 fromTokenId, address to, uint256 value)
        public payable virtual override returns (uint256 newTokenId)
    {
        if (!_inForceOp && to != address(0)) {
            require(isWhitelisted(to), "EwpgERC3525: recipient not whitelisted");
        }
        return super.transferFrom(fromTokenId, to, value);
    }

    /// @dev ERC-3525 token IDs are ERC-721 NFTs. Guard ownership moves independently from
    ///      value transfers so transferring the whole token cannot bypass the series or holder
    ///      controls below. Mint and burn retain their existing zero-address semantics; their
    ///      dedicated entry points perform the applicable issuance/burn checks.
    function _update(address to, uint256 tokenId, address auth)
        internal virtual override returns (address)
    {
        address from = _ownerOf(tokenId);
        if (!_inForceOp && from != address(0) && to != address(0)) {
            _requireTransferable(from, to);
            require(!_frozenTokens[tokenId], "EwpgERC3525: token is frozen");
            require(!_slots[slotOf(tokenId)].paused, "EwpgERC3525: slot is paused");
            require(isWhitelisted(to), "EwpgERC3525: recipient not whitelisted");
        }
        return super._update(to, tokenId, auth);
    }

    function _transferValue(uint256 fromTokenId, uint256 toTokenId, uint256 value)
        internal virtual override
    {
        if (!_inForceOp) {
            require(!isPaused(), "EwpgERC3525: global transfers are paused");
            require(!_slots[slotOf(fromTokenId)].paused, "EwpgERC3525: slot is paused");
            require(!_frozenTokens[fromTokenId], "EwpgERC3525: source token is frozen");
            require(!_frozenTokens[toTokenId], "EwpgERC3525: destination token is frozen");
            address fromOwner = ownerOf(fromTokenId);
            address toOwner = ownerOf(toTokenId);
            require(!isFrozen(fromOwner), "EwpgERC3525: source address is frozen");
            require(!isFrozen(toOwner), "EwpgERC3525: destination address is frozen");
        }
        super._transferValue(fromTokenId, toTokenId, value);
    }
}
