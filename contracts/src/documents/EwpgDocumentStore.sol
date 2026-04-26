// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/Ownable.sol";
import "./EwpgDocumentManagement.sol";

/**
 * @title EwpgDocumentStore
 * @notice Standalone document store deployed as a companion to EwpgERC3643 (T-REX) suites.
 *
 * Because EwpgERC3643 is a T-REX upgradeable proxy it is unsafe to add storage slots via
 * inheritance. Instead, EwpgTREXFactory deploys one EwpgDocumentStore per suite and
 * records its address in `docStoreByAssetId`. The registry wallet (owner) calls
 * `setDocument` here to publish the term sheet URI and hash on-chain.
 *
 * The backend reads the store address from the factory and calls `getDocument(TERM_SHEET())`
 * to retrieve the URI, then fetches and caches the content for BaFin compliance.
 */
contract EwpgDocumentStore is Ownable, EwpgDocumentManagement {
    /// @notice Links this store to the off-chain registry asset record.
    bytes32 public immutable assetId;

    event DocumentStoreCreated(bytes32 indexed assetId, address indexed owner);

    constructor(bytes32 _assetId, address _owner) Ownable(_owner) {
        assetId = _assetId;
        emit DocumentStoreCreated(_assetId, _owner);
    }

    /// @inheritdoc EwpgDocumentManagement
    function _requireDocumentAdmin() internal view override {
        require(msg.sender == owner(), "EwpgDocumentStore: caller is not owner");
    }
}
