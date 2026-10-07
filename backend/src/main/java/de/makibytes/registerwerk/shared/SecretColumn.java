package de.makibytes.registerwerk.shared;

import java.util.List;

/** One database column that holds {@link EnvelopeCipher} values, read in key order and updated compare-and-set. */
public interface SecretColumn {

    /** {@code key} identifies the row (never the secret); {@code value} is the stored {@code enc:v1:} string. */
    record Row(String key, String value) { }

    /** Up to {@code limit} envelope values whose key sorts after {@code afterKey} ({@code null} = from the start). */
    List<Row> page(String afterKey, int limit);

    /** Replaces {@code expected} by {@code replacement} for {@code key}; false if the row changed meanwhile. */
    boolean replace(String key, String expected, String replacement);
}
