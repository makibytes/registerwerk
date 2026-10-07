package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import de.makibytes.registerwerk.audit.api.AuditAnchorSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@DisplayName("AuditAnchorService with an external sink (T6-17)")
class AuditAnchorServiceSinkTest {

    private static AuditAnchor anchor(String date) {
        return new AuditAnchor(LocalDate.parse(date), 7, "aa".repeat(32), null);
    }

    /** Fake sink: remembers published anchors, optionally failing the first N publishes. */
    private static final class FakeSink implements AuditAnchorSink {
        final List<AuditAnchor> published = new ArrayList<>();
        int failFirst;
        AuditAnchor latest;

        @Override
        public void publish(AuditAnchor a) {
            if (failFirst-- > 0) {
                throw new IllegalStateException("store unavailable");
            }
            published.add(a);
        }

        @Override
        public Optional<AuditAnchor> latest() {
            return Optional.ofNullable(latest);
        }
    }

    private JdbcTemplate jdbc(List<AuditAnchor> newestFirst) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForMap(anyString())).thenReturn(Map.of("entry_hash", new byte[32], "sequence_no", 7L));
        Mockito.when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        Mockito.doReturn(newestFirst).when(jdbc).query(anyString(), any(RowMapper.class), any(), any());
        return jdbc;
    }

    @Test
    @DisplayName("a sink failure never fails anchoring: the local anchor is kept and the call returns normally")
    void sinkFailureDoesNotBlockAnchoring() {
        FakeSink sink = new FakeSink();
        sink.failFirst = 1;
        AuditAnchorService svc = new AuditAnchorService(jdbc(List.of()), Optional.empty(), Optional.of(sink));
        assertThatCode(svc::anchorNow).doesNotThrowAnyException();
        assertThat(svc.anchorNow()).isTrue();
        assertThat(sink.published).hasSize(1);
    }

    @Test
    @DisplayName("pending anchors newer than the external newest are re-published oldest first")
    void pendingAreRepublishedInOrder() {
        FakeSink sink = new FakeSink();
        sink.latest = anchor("2026-10-01");
        AuditAnchorService svc = new AuditAnchorService(
                jdbc(List.of(anchor("2026-10-03"), anchor("2026-10-02"))), Optional.empty(), Optional.of(sink));
        assertThat(svc.publishPending(sink)).isEqualTo(2);
        assertThat(sink.published).extracting(AuditAnchor::anchorDate)
                .containsExactly(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03"));
    }

    @Test
    @DisplayName("the retry stops at the first failure so ordering is preserved")
    void retryStopsAtFirstFailure() {
        FakeSink sink = new FakeSink();
        sink.failFirst = 1;
        AuditAnchorService svc = new AuditAnchorService(
                jdbc(List.of(anchor("2026-10-03"), anchor("2026-10-02"))), Optional.empty(), Optional.of(sink));
        assertThat(svc.publishPending(sink)).isZero();
        assertThat(sink.published).isEmpty();
    }
}
