package de.makibytes.registerwerk.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** {@link EnvelopeSecretInventory} over one or more {@link SecretColumn}s that share one secret type. */
public class ColumnSecretInventory implements EnvelopeSecretInventory {

    private static final Logger log = LoggerFactory.getLogger(ColumnSecretInventory.class);

    private final String type;
    private final EnvelopeCipher cipher;
    private final List<SecretColumn> columns;
    private final int scanPageSize;

    public ColumnSecretInventory(String type, EnvelopeCipher cipher, List<SecretColumn> columns, int scanPageSize) {
        this.type = type;
        this.cipher = cipher;
        this.columns = columns;
        this.scanPageSize = Math.max(1, scanPageSize);
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Map<String, Long> countByKekVersion() {
        Map<String, Long> counts = new TreeMap<>();
        for (SecretColumn column : columns) {
            forEachRow(column, scanPageSize, row -> counts.merge(cipher.versionLabel(row.value()), 1L, Long::sum));
        }
        return counts;
    }

    @Override
    public RewrapOutcome rewrapStale(int pageSize) {
        int[] done = {0, 0};
        for (SecretColumn column : columns) {
            forEachRow(column, pageSize, row -> {
                if (!cipher.needsRewrap(row.value())) {
                    return;
                }
                try {
                    String replacement = cipher.rewrap(row.value());
                    if (column.replace(row.key(), row.value(), replacement)) {
                        done[0]++;
                    }
                    // else: the row changed under us (a newer write already used the active version); next run decides
                } catch (RuntimeException e) {
                    done[1]++;
                    log.warn("KEK re-wrap failed for {} row {} ({})", type, row.key(), e.getClass().getSimpleName());
                }
            });
        }
        return new RewrapOutcome(done[0], done[1]);
    }

    private static void forEachRow(SecretColumn column, int pageSize, Consumer<SecretColumn.Row> action) {
        String cursor = null;
        while (true) {
            List<SecretColumn.Row> page = column.page(cursor, pageSize);
            for (SecretColumn.Row row : page) {
                action.accept(row);
            }
            if (page.size() < pageSize) {
                return;
            }
            cursor = page.get(page.size() - 1).key();
        }
    }
}
