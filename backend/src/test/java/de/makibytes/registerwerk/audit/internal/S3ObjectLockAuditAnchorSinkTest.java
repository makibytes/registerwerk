package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@DisplayName("S3ObjectLockAuditAnchorSink (T6-17) without a store")
class S3ObjectLockAuditAnchorSinkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:05:00Z"), ZoneOffset.UTC);
    private static final AuditAnchor ANCHOR = new AuditAnchor(LocalDate.parse("2026-10-07"), 42, "ab".repeat(32), "cd".repeat(64));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final S3Client s3 = Mockito.mock(S3Client.class);

    private S3ObjectLockAuditAnchorSink sink(String mode) {
        return new S3ObjectLockAuditAnchorSink(s3, "anchors", "prod/audit", mode, 30, false, CLOCK, registry);
    }

    private double failures(String op) {
        return registry.counter("registerwerk_audit_anchor_sink_failures_total", "operation", op).count();
    }

    @Test
    @DisplayName("publish writes a retention-locked, never-overwriting object keyed by date under the normalised prefix")
    void publishSetsRetentionHeaders() {
        sink("COMPLIANCE").publish(ANCHOR);
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        Mockito.verify(s3).putObject(req.capture(), body.capture());
        assertThat(req.getValue().bucket()).isEqualTo("anchors");
        assertThat(req.getValue().key()).isEqualTo("prod/audit/2026-10-07.json");
        assertThat(req.getValue().objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(req.getValue().objectLockRetainUntilDate()).isEqualTo(Instant.parse("2026-11-06T00:05:00Z"));
        assertThat(req.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(S3ObjectLockAuditAnchorSink.fromJson(
                S3ObjectLockAuditAnchorSink.toJson(ANCHOR).getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(ANCHOR);
        assertThat(failures("publish")).isZero();
    }

    @Test
    @DisplayName("GOVERNANCE is selectable; anything else is rejected at startup")
    void modes() {
        sink("governance").publish(ANCHOR);
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().objectLockMode()).isEqualTo(ObjectLockMode.GOVERNANCE);
        assertThatThrownBy(() -> sink("NONE")).isInstanceOf(IllegalStateException.class).hasMessageContaining("COMPLIANCE or GOVERNANCE");
        assertThatThrownBy(() -> new S3ObjectLockAuditAnchorSink(s3, " ", "p", "COMPLIANCE", 1, false, CLOCK, registry))
                .hasMessageContaining("bucket");
    }

    @Test
    @DisplayName("a failing store raises the alert metric and surfaces to the anchor job; an already-present date (412) is success")
    void failureCountsAndPreconditionFailedIsIdempotent() {
        Mockito.when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(503).message("down").build());
        assertThatThrownBy(() -> sink("COMPLIANCE").publish(ANCHOR)).isInstanceOf(AwsServiceException.class);
        assertThat(failures("publish")).isEqualTo(1.0);

        Mockito.when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(412).message("exists").build());
        sink("COMPLIANCE").publish(ANCHOR);
        assertThat(failures("publish")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unreadable store yields no external anchor, counts a read failure and does not throw")
    void readFailureIsCountedNotThrown() {
        Mockito.when(s3.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenThrow(new RuntimeException("connect timed out"));
        assertThat(sink("COMPLIANCE").latest()).isEmpty();
        assertThat(failures("read")).isEqualTo(1.0);
    }
}
