package de.makibytes.registerwerk.asset.api;

public enum OnchainLevel {
    /** No on-chain data; only PostgreSQL is used. */
    NONE,
    /** Asset token emitted for primary market; issuer sends tokens to whitelisted investors. */
    SIMPLE,
    /** On-chain is the primary storage layer; provides mint control and auto-approval. */
    CONTROL
}
