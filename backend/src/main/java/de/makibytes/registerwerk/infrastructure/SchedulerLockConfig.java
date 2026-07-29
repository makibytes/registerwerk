package de.makibytes.registerwerk.infrastructure;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Enables ShedLock so every {@code @Scheduled} job in the application is serialized
 * across threads AND backend instances via a Postgres row lock (V3__shedlock.sql).
 *
 * <p>Without this, scaling the backend out to more than one instance for hot-failover
 * would make every on-chain tx poller, indexer sync job, and reporting cron run
 * concurrently on each instance — double-submitting on-chain transactions and
 * double-processing events. Annotate each {@code @Scheduled} method with
 * {@code @SchedulerLock(name = "...")}; unannotated methods are NOT locked.
 *
 * <p>{@code defaultLockAtMostFor} is a safety net if an instance dies mid-job without
 * releasing the lock — after this long, another instance may re-acquire it even without
 * an explicit release. Individual jobs override it via {@code @SchedulerLock(lockAtMostFor=...)}
 * where their expected runtime differs materially from this default.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
