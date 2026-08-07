package de.makibytes.registerwerk.wallet.api;

/**
 * Port for where {@link WalletStorage}'s encrypted keystore/DEK-sidecar bytes physically live.
 * In production, wire {@code PostgresKeystoreBlobStore} (selected via
 * {@code registerwerk.wallet.storage-backend=POSTGRES}) so wallet key material does not pin
 * the backend to a specific node — the filesystem-backed default requires a shared volume
 * mounted into every replica, which cannot co-schedule with pod anti-affinity across nodes.
 * Every value stored/returned here is already encrypted by the caller; this port only moves
 * opaque bytes.
 */
public interface KeystoreBlobStore {

    /** Human-readable name logged at startup. */
    String name();

    /** Writes (or overwrites) the blob at {@code relativePath}. */
    void write(String relativePath, byte[] content);

    /** Reads the blob at {@code relativePath}, throwing if it does not exist. */
    byte[] read(String relativePath);

    boolean exists(String relativePath);

    /** No-op if the blob does not exist. */
    void delete(String relativePath);
}
