// SPDX-License-Identifier: MIT
// Minimal ERC-3525 base implementation.
// Based on the EIP-3525 authored by Solv Protocol (MIT licensed).
// Vendored to avoid external dependency. Only core functions included.
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import "./IERC3525.sol";

/// @title ERC3525
/// @notice Minimal semi-fungible token base. Tokens have (id, slot, value).
///         Tokens within the same slot can transfer value between each other.
abstract contract ERC3525 is ERC721, IERC3525 {
    // ── Storage ───────────────────────────────────────────────────────────────

    struct TokenData {
        uint256 id;
        uint256 slot;
        uint256 balance;
    }

    uint256 private _nextTokenId;
    mapping(uint256 => TokenData) private _tokenData;
    mapping(uint256 => mapping(address => uint256)) private _valueApprovals;

    // ── Constructor ───────────────────────────────────────────────────────────

    constructor(string memory name, string memory symbol) ERC721(name, symbol) {
        _nextTokenId = 1;
    }

    // ── Interface support ─────────────────────────────────────────────────────

    function supportsInterface(bytes4 interfaceId)
        public view virtual override(ERC721, IERC165)
        returns (bool)
    {
        return interfaceId == type(IERC3525).interfaceId || super.supportsInterface(interfaceId);
    }

    // ── ERC-3525 view ─────────────────────────────────────────────────────────

    function slotOf(uint256 tokenId) public view virtual override returns (uint256) {
        _requireOwned(tokenId);
        return _tokenData[tokenId].slot;
    }

    function balanceOf(uint256 tokenId) public view virtual override returns (uint256) {
        _requireOwned(tokenId);
        return _tokenData[tokenId].balance;
    }

    function allowance(uint256 tokenId, address operator)
        public view virtual override returns (uint256)
    {
        return _valueApprovals[tokenId][operator];
    }

    // ── Approval ──────────────────────────────────────────────────────────────

    function approve(uint256 tokenId, address operator, uint256 value)
        public payable virtual override
    {
        address owner = ownerOf(tokenId);
        require(msg.sender == owner || isApprovedForAll(owner, msg.sender),
                "ERC3525: caller is not owner nor approved-for-all");
        _valueApprovals[tokenId][operator] = value;
        emit ApprovalValue(tokenId, operator, value);
    }

    // ── Value transfer ────────────────────────────────────────────────────────

    function transferFrom(uint256 fromTokenId, uint256 toTokenId, uint256 value)
        public payable virtual override
    {
        _requireSenderApproved(fromTokenId, value);
        require(_tokenData[fromTokenId].slot == _tokenData[toTokenId].slot,
                "ERC3525: tokens in different slots");
        _transferValue(fromTokenId, toTokenId, value);
    }

    function transferFrom(uint256 fromTokenId, address to, uint256 value)
        public payable virtual override
        returns (uint256 newTokenId)
    {
        _requireSenderApproved(fromTokenId, value);
        // Create the destination position without issuing value, then move the
        // requested value through the same transfer hook used by token-to-token
        // transfers. Minting with `value` here would credit the destination before
        // `_transferValue` and make it impossible to conserve value with one debit.
        newTokenId = _mintToken(to, _tokenData[fromTokenId].slot, 0);
        _transferValue(fromTokenId, newTokenId, value);
        return newTokenId;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    function _mintToken(address to, uint256 slot, uint256 value)
        internal virtual returns (uint256 tokenId)
    {
        tokenId = _nextTokenId++;
        _safeMint(to, tokenId);
        _tokenData[tokenId] = TokenData({ id: tokenId, slot: slot, balance: value });
        emit TransferValue(0, tokenId, value);
        emit SlotChanged(tokenId, 0, slot);
    }

    function _burnToken(uint256 tokenId) internal virtual {
        require(_tokenData[tokenId].balance == 0, "ERC3525: token has remaining value");
        delete _tokenData[tokenId];
        _burn(tokenId);
    }

    function _transferValue(uint256 fromTokenId, uint256 toTokenId, uint256 value) internal virtual {
        require(_tokenData[fromTokenId].balance >= value, "ERC3525: insufficient balance");
        _tokenData[fromTokenId].balance -= value;
        _tokenData[toTokenId].balance += value;
        emit TransferValue(fromTokenId, toTokenId, value);
    }

    /// @notice Irrevocably destroys `value` from `tokenId`'s balance without crediting any
    ///         other token (a burn sink, mirroring `_mintToken`'s `0 -> tokenId` credit).
    ///         Used by forced-burn (Einziehung) flows where the value must disappear from
    ///         circulation rather than move to another holder.
    function _burnValue(uint256 tokenId, uint256 value) internal virtual {
        require(_tokenData[tokenId].balance >= value, "ERC3525: insufficient balance");
        _tokenData[tokenId].balance -= value;
        emit TransferValue(tokenId, 0, value);
    }

    function _requireSenderApproved(uint256 tokenId, uint256 value) internal {
        address owner = ownerOf(tokenId);
        if (msg.sender != owner && !isApprovedForAll(owner, msg.sender)) {
            require(_valueApprovals[tokenId][msg.sender] >= value,
                    "ERC3525: insufficient value allowance");
            _valueApprovals[tokenId][msg.sender] -= value;
        }
    }
}
