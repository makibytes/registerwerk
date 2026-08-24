package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KeystoreBlobStore;
import de.makibytes.registerwerk.wallet.api.WalletStorage.WalletStorageException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Default {@link KeystoreBlobStore} backend — reads/writes under
 * {@link WalletProperties#getStorageDir()}. Fine for local/single-instance dev; a
 * multi-replica deployment should select {@code PostgresKeystoreBlobStore} instead
 * (see {@link KeystoreBlobStore}'s class Javadoc for why).
 */
@Component
@ConditionalOnMissingBean(name = "postgresKeystoreBlobStore")
class FilesystemKeystoreBlobStore implements KeystoreBlobStore {

    private final WalletProperties props;

    FilesystemKeystoreBlobStore(WalletProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "FILESYSTEM";
    }

    @Override
    public void write(String relativePath, byte[] content) {
        try {
            writeAtomically(storagePath(relativePath), content);
        } catch (IOException e) {
            throw new WalletStorageException("Failed to write keystore blob at " + relativePath, e);
        }
    }

    @Override
    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(storagePath(relativePath));
        } catch (IOException e) {
            throw new WalletStorageException("Failed to read keystore blob at " + relativePath, e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(storagePath(relativePath));
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(storagePath(relativePath));
        } catch (IOException e) {
            throw new WalletStorageException("Failed to delete keystore blob at " + relativePath, e);
        }
    }

    private Path storagePath(String relativePath) {
        return Path.of(props.getStorageDir()).resolve(relativePath);
    }

    /** Writes via a temp file + atomic rename so a crash mid-write cannot corrupt the original. */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
