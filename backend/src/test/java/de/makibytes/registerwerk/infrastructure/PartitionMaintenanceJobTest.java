package de.makibytes.registerwerk.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PartitionMaintenanceJob unit tests (7A-11)")
class PartitionMaintenanceJobTest {

    /** Minimal transaction manager: the job only needs begin/commit/rollback to exist. */
    static class NoopTm extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private final EntityManager em = mock(EntityManager.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<String> sql = new ArrayList<>();
    private PartitionMaintenanceJob job;
    private Object lockResult = Boolean.TRUE;

    @BeforeEach
    void init() {
        job = new PartitionMaintenanceJob(new NoopTm(), registry, mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(job, "em", em);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return query;
        });
        when(query.getSingleResult()).thenAnswer(inv ->
                sql.get(sql.size() - 1).contains("pg_try_advisory_xact_lock") ? lockResult : null);
    }

    @Test
    @DisplayName("startup run ensures token_transfer and blockchain_transaction, never audit_event")
    void onStartupEnsuresBothPartitionedTables() {
        job.onStartup();
        assertThat(sql).anySatisfy(s -> assertThat(s).contains("'token_transfer'").contains("'occurred_at'"))
                .anySatisfy(s -> assertThat(s).contains("'blockchain_transaction'").contains("'created_at'"))
                .noneMatch(s -> s.contains("audit_event"));
    }

    @Test
    @DisplayName("a failing ensure (row in the DEFAULT partition) is caught and counted, startup is not aborted")
    void failureDoesNotAbortStartup() {
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.contains("rw_ensure_monthly_partitions")))
                .thenThrow(new IllegalStateException("updated partition constraint for default partition would be violated"));
        assertThatCode(() -> job.onStartup()).doesNotThrowAnyException();
        assertThat(registry.get("registerwerk_partition_ensure_failures_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an instance that does not get the advisory lock skips the ensure")
    void lockLoserSkips() {
        lockResult = Boolean.FALSE;
        job.onStartup();
        assertThat(sql).noneMatch(s -> s.contains("rw_ensure_monthly_partitions"));
    }
}
