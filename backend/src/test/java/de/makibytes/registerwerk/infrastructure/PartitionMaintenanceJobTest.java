package de.makibytes.registerwerk.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PartitionMaintenanceJob unit tests")
class PartitionMaintenanceJobTest {

    @Mock
    private EntityManager em;

    private PartitionMaintenanceJob job;

    private void init() throws Exception {
        job = new PartitionMaintenanceJob();
        Field f = PartitionMaintenanceJob.class.getDeclaredField("em");
        f.setAccessible(true);
        f.set(job, em);

        Query query = mock(Query.class);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(null);
    }

    @Test
    @DisplayName("startup run ensures partitions for both token_transfer and blockchain_transaction, never audit_event")
    void onStartupEnsuresBothPartitionedTables() throws Exception {
        init();

        job.onStartup();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em, times(2)).createNativeQuery(sqlCaptor.capture());

        assertThat(sqlCaptor.getAllValues())
                .anySatisfy(sql -> assertThat(sql).contains("'token_transfer'").contains("'occurred_at'"))
                .anySatisfy(sql -> assertThat(sql).contains("'blockchain_transaction'").contains("'created_at'"))
                .noneMatch(sql -> sql.contains("audit_event"));
    }

    @Test
    @DisplayName("scheduled monthly run does the same ensure work as the startup run")
    void ensurePartitionsMatchesStartupBehavior() throws Exception {
        init();

        job.ensurePartitions();

        verify(em, times(2)).createNativeQuery(org.mockito.ArgumentMatchers.anyString());
    }
}
