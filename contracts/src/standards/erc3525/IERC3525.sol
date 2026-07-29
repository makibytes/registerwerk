// SPDX-License-Identifier: MIT
// Based on EIP-3525 authored by Solv Protocol. Vendored to avoid external dependency.
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/token/ERC721/IERC721.sol";

/// @title IERC3525 — Semi-Fungible Token standard
/// @notice Each token has a (id, slot, value) triple: unique id like ERC-721,
///         a fungible `value` like ERC-20, and a `slot` that groups tokens into series.
interface IERC3525 is IERC721 {
    // ── Events ────────────────────────────────────────────────────────────────

    event TransferValue(uint256 indexed fromTokenId, uint256 indexed toTokenId, uint256 value);
    event ApprovalValue(uint256 indexed tokenId, address indexed operator, uint256 value);
    event SlotChanged(uint256 indexed tokenId, uint256 indexed oldSlot, uint256 indexed newSlot);

    // ── Slot-level operations ─────────────────────────────────────────────────

    function slotOf(uint256 tokenId) external view returns (uint256);
    function balanceOf(uint256 tokenId) external view returns (uint256 value);
    function allowance(uint256 tokenId, address operator) external view returns (uint256);

    function approve(uint256 tokenId, address operator, uint256 value) external payable;

    /// @notice Transfer `value` units from `fromTokenId` to `toTokenId` (must be same slot).
    function transferFrom(uint256 fromTokenId, uint256 toTokenId, uint256 value) external payable;

    /// @notice Transfer `value` units from `fromTokenId` to a new token owned by `to` in same slot.
    function transferFrom(uint256 fromTokenId, address to, uint256 value) external payable returns (uint256 newTokenId);
}
