package de.makibytes.registerwerk.asset.api;

public enum AssetDocumentSource {
    /** Document was uploaded directly via the API. */
    UPLOAD,
    /** Document URI and hash were read from an ERC-1643 getDocument() call on the token contract. */
    ONCHAIN_ERC1643,
    /** URI was read from ERC-721 tokenURI() or ERC-1155 uri(). */
    ONCHAIN_TOKEN_URI,
    /** URI was read from the Solana Metaplex metadata account. */
    ONCHAIN_SOLANA
}
