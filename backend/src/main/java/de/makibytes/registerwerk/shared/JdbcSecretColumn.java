package de.makibytes.registerwerk.shared;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@link SecretColumn} over a PostgreSQL table. Table and column names are compile-time constants of the owning
 * module, never request data. Optionally keeps a "key id" column next to the value in step.
 */
public final class JdbcSecretColumn implements SecretColumn {

    private final JdbcTemplate jdbc;
    private final String pageSql;
    private final String updateSql;
    private final Supplier<String> kid;

    public JdbcSecretColumn(JdbcTemplate jdbc, String table, String keyColumn, String valueColumn) {
        this(jdbc, table, keyColumn, valueColumn, null, null);
    }

    public JdbcSecretColumn(JdbcTemplate jdbc, String table, String keyColumn, String valueColumn,
                            String kidColumn, Supplier<String> kid) {
        this.jdbc = jdbc;
        this.kid = kid;
        this.pageSql = "SELECT " + keyColumn + "::text AS k, " + valueColumn + " AS v FROM " + table
                + " WHERE " + valueColumn + " LIKE 'enc:v1:%' AND (?::text IS NULL OR " + keyColumn + "::text > ?)"
                + " ORDER BY " + keyColumn + "::text LIMIT ?";
        this.updateSql = "UPDATE " + table + " SET " + valueColumn + " = ?"
                + (kidColumn == null ? "" : ", " + kidColumn + " = ?")
                + " WHERE " + keyColumn + "::text = ? AND " + valueColumn + " = ?";
    }

    @Override
    public List<Row> page(String afterKey, int limit) {
        return jdbc.query(pageSql, (rs, i) -> new Row(rs.getString("k"), rs.getString("v")), afterKey, afterKey, limit);
    }

    @Override
    public boolean replace(String key, String expected, String replacement) {
        int n = kid == null
                ? jdbc.update(updateSql, replacement, key, expected)
                : jdbc.update(updateSql, replacement, kid.get(), key, expected);
        return n == 1;
    }
}
