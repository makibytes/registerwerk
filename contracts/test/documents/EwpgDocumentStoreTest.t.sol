// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../../src/documents/EwpgDocumentStore.sol";

/// @notice EwpgDocumentStore is deployed inline by EwpgTREXFactory.deployEwpgSuite (one per
///         asset, alongside the T-REX suite) but that deployment path never asserts on the
///         store's own document-management behavior — this suite exercises it directly.
contract EwpgDocumentStoreTest is Test {
    EwpgDocumentStore store;
    address owner = makeAddr("registryWallet");
    bytes32 constant ASSET_ID = keccak256("doc-store-asset-1");

    function setUp() public {
        store = new EwpgDocumentStore(ASSET_ID, owner);
    }

    function test_assetIdIsSet() public view {
        assertEq(store.assetId(), ASSET_ID);
    }

    function test_setDocument_ownerCanPublishTermSheet() public {
        bytes32 name = store.TERM_SHEET();
        bytes32 hash = keccak256("term-sheet-content");

        vm.prank(owner);
        store.setDocument(name, "ipfs://term-sheet", hash);

        (string memory uri, bytes32 documentHash, uint256 lastModified) = store.getDocument(name);
        assertEq(uri, "ipfs://term-sheet");
        assertEq(documentHash, hash);
        assertEq(lastModified, block.timestamp);
    }

    function test_setDocument_revertsForNonOwner() public {
        bytes32 name = store.TERM_SHEET();
        vm.expectRevert("EwpgDocumentStore: caller is not owner");
        store.setDocument(name, "ipfs://term-sheet", keccak256("x"));
    }

    function test_removeDocument_ownerCanRemove() public {
        bytes32 name = store.TERM_SHEET();
        vm.startPrank(owner);
        store.setDocument(name, "ipfs://term-sheet", keccak256("x"));
        store.removeDocument(name);
        vm.stopPrank();

        (string memory uri,,) = store.getDocument(name);
        assertEq(uri, "");
        assertEq(store.getAllDocuments().length, 0);
    }

    function test_removeDocument_revertsForNonOwner() public {
        bytes32 name = store.TERM_SHEET();
        vm.prank(owner);
        store.setDocument(name, "ipfs://term-sheet", keccak256("x"));

        vm.expectRevert("EwpgDocumentStore: caller is not owner");
        store.removeDocument(name);
    }
}
