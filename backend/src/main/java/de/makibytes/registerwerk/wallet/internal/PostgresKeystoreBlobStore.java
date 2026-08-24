package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KeystoreBlobStore;
import de.makibytes.registerwerk.wallet.api.WalletStorage.WalletStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link KeystoreBlobStore} backed by the {@code wallet_keystore_blob} table (V3 migration).
 * Selected via {@code registerwerk.wallet.storage-backend=POSTGRES} — makes the backend
 * horizontally scalable without a shared filesystem volume, since every replica already
 * shares the same Postgres instance. A single {@code INSERT ... ON CONFLICT} is atomic, so
 * there is no crash-mid-write corruption risk to guard against here (unlike the filesystem
 * backend's temp-file-then-rename dance).
 */
@Component("postgresKeystoreBlobStore")
@ConditionalOnProperty(name = "registerwerk.wallet.storage-backend", havingValue = "POSTGRES")
class PostgresKeystoreBlobStore implements KeystoreBlobStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresKeystoreBlobStore.class);

    private final JdbcTemplate jdbc;

    PostgresKeystoreBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        log.info("Wallet keystore storage backend: POSTGRES (wallet_keystore_blob)");
    }

    @Override
    public String name() {
        return "POSTGRES";
    }

    @Override
    public void write(String relativePath, byte[] content) {
        jdbc.update("""
                INSERT INTO wallet_keystore_blob (relative_path, content, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (relative_path) DO UPDATE SET
                    content = EXCLUDED.content,
                    updated_at = now()
                """, relativePath, content);
    }

    @Override
    public byte[] read(String relativePath) {
        byte[] content = jdbc.query(
                "SELECT content FROM wallet_keystore_blob WHERE relative_path = ?",
                rs -> rs.next() ? rs.getBytes(1) : null,
                relativePath);
        if (content == null) {
            throw new WalletStorageException("No keystore blob found at " + relativePath);
        }
        return content;
    }

    @Override
    public boolean exists(String relativePath) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM wallet_keystore_blob WHERE relative_path = ?",
                Integer.class, relativePath);
        return count != null && count > 0;
    }

    @Override
    public void delete(String relativePath) {
        jdbc.update("DELETE FROM wallet_keystore_blob WHERE relative_path = ?", relativePath);
    }
}
