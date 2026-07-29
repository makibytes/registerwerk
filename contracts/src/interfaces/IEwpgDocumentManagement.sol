// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/**
 * @title IEwpgDocumentManagement
 * @notice ERC-1643-based document management intended to link security-token documents.
 *         This interface does not establish eWpG register or document-law compliance.
 *         Enables on-chain storage of document URIs and content hashes, allowing
 *         regulators (e.g. BaFin) to retrieve legal documents such as term sheets
 *         directly from the token contract.
 *
 * Well-known document name: keccak256("TERM_SHEET") — use the TERM_SHEET constant.
 */
interface IEwpgDocumentManagement {

    struct DocRecord {
        string uri;
        bytes32 documentHash; // keccak256 of the document content
        uint256 lastModified; // block.timestamp of the last setDocument call
    }

    /// @notice Canonical bytes32 name for the term sheet document.
    // solhint-disable-next-line var-name-mixedcase
    function TERM_SHEET() external pure returns (bytes32);

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * @notice Returns the URI, content hash, and last-modified timestamp for a document.
     * @param name   bytes32 identifier (e.g. TERM_SHEET constant).
     * @return uri          Location of the document (https://, ipfs://, ar://, data:…).
     * @return documentHash keccak256 of the document content for integrity verification.
     * @return lastModified block.timestamp of the last update.
     */
    function getDocument(bytes32 name)
        external view
        returns (string memory uri, bytes32 documentHash, uint256 lastModified);

    /**
     * @notice Returns all document names that have been set on this contract.
     */
    function getAllDocuments() external view returns (bytes32[] memory names);

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * @notice Creates or updates a document.
     * @param name          bytes32 identifier (e.g. TERM_SHEET).
     * @param uri           Document location URI.
     * @param documentHash  keccak256 of the document content.
     */
    function setDocument(bytes32 name, string calldata uri, bytes32 documentHash) external;

    /**
     * @notice Removes a document.
     * @param name  bytes32 identifier of the document to remove.
     */
    function removeDocument(bytes32 name) external;

    // ── Events ────────────────────────────────────────────────────────────────

    event DocumentUpdated(bytes32 indexed name, string uri, bytes32 documentHash);
    event DocumentRemoved(bytes32 indexed name);
}
