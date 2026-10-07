package de.makibytes.registerwerk;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Lightweight real-PostgreSQL fixture for DB-level regression tests that do not need a Spring context:
 * runs every Flyway migration against the container and hands out a plain {@link JdbcTemplate}.
 */
public final class MigratedDb {

    private final DataSource dataSource;

    private MigratedDb(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static MigratedDb migrate(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return new MigratedDb(ds);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
