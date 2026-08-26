package de.makibytes.registerwerk;

/**
 * Single source of truth for the PostgreSQL image every Testcontainers-backed integration test
 * pins to. Keep this in lockstep with {@code docker-compose.yml}'s {@code postgres} service and
 * {@code .github/workflows/backend.yml}'s CI service container — all three must run the same
 * major/minor version so a test-only behavior difference can never mask (or manufacture) a
 * PostgreSQL-version-sensitive bug.
 */
public final class TestPostgres {
    public static final String IMAGE = "postgres:18.6-alpine";

    private TestPostgres() {
    }
}
