// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../interfaces/IEwpgDocumentManagement.sol";

/**
 * @title EwpgDocumentManagement
 * @notice Abstract ERC-1643 document management base for eWpG security tokens.
 *         Inheriting contracts must override `_requireDocumentAdmin()` to enforce
 *         their own access control (e.g. onlyRegistry or onlyOwner).
 *
 * Storage layout uses a mapping + names array to support enumeration.
 * Removing a document swaps the deleted entry with the last element to keep
 * the array dense without gaps.
 */
abstract contract EwpgDocumentManagement is IEwpgDocumentManagement {

    mapping(bytes32 => DocRecord) private _docs;
    bytes32[] private _docNames;
    mapping(bytes32 => uint256) private _docIndex; // name → 1-based index in _docNames

    // ── IEwpgDocumentManagement ───────────────────────────────────────────────

    /// @inheritdoc IEwpgDocumentManagement
    function TERM_SHEET() public pure override returns (bytes32) {
        return keccak256("TERM_SHEET");
    }

    /// @inheritdoc IEwpgDocumentManagement
    function getDocument(bytes32 name)
        external view override
        returns (string memory uri, bytes32 documentHash, uint256 lastModified)
    {
        DocRecord storage rec = _docs[name];
        return (rec.uri, rec.documentHash, rec.lastModified);
    }

    /// @inheritdoc IEwpgDocumentManagement
    function getAllDocuments() external view override returns (bytes32[] memory names) {
        return _docNames;
    }

    /// @inheritdoc IEwpgDocumentManagement
    function setDocument(bytes32 name, string calldata uri, bytes32 documentHash) external override {
        _requireDocumentAdmin();
        if (_docIndex[name] == 0) {
            // new document: append to names array
            _docNames.push(name);
            _docIndex[name] = _docNames.length; // 1-based
        }
        _docs[name] = DocRecord({ uri: uri, documentHash: documentHash, lastModified: block.timestamp });
        emit DocumentUpdated(name, uri, documentHash);
    }

    /// @inheritdoc IEwpgDocumentManagement
    function removeDocument(bytes32 name) external override {
        _requireDocumentAdmin();
        uint256 idx = _docIndex[name];
        require(idx != 0, "EwpgDocumentManagement: document not found");

        // Swap with last element and pop
        uint256 lastIdx = _docNames.length;
        if (idx < lastIdx) {
            bytes32 last = _docNames[lastIdx - 1];
            _docNames[idx - 1] = last;
            _docIndex[last] = idx;
        }
        _docNames.pop();
        delete _docIndex[name];
        delete _docs[name];
        emit DocumentRemoved(name);
    }

    // ── Internal hook ─────────────────────────────────────────────────────────

    /**
     * @notice Reverts if the caller is not authorised to manage documents.
     *         Must be overridden by each inheriting contract.
     */
    function _requireDocumentAdmin() internal view virtual;
}
