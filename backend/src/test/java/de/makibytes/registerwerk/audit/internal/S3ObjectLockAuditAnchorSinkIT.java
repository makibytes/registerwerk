package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("S3ObjectLockAuditAnchorSink against a real Object Lock store (MinIO)")
class S3ObjectLockAuditAnchorSinkIT {

    static GenericContainer<?> minio;
    static S3Client admin;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeAll
    static void start() {
        minio = TestMinio.container();
        minio.start();
        admin = TestMinio.client(minio);
    }

    @AfterAll
    static void stop() {
        admin.close();
        minio.stop();
    }

    private S3ObjectLockAuditAnchorSink sink(String bucket, String mode) {
        S3ObjectLockAuditAnchorSink s = new S3ObjectLockAuditAnchorSink(TestMinio.client(minio), bucket, "anchors/", mode, 2,
                true, Clock.systemUTC(), registry);
        s.verifyBucket();
        return s;
    }

    private static String bucket() {
        return "audit-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private double failures() {
        return registry.find("registerwerk_audit_anchor_sink_failures_total").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static AuditAnchor anchor(String date, long seq, String hash) {
        return new AuditAnchor(LocalDate.parse(date), seq, hash, "cd".repeat(64));
    }

    @Test
    @DisplayName("publish stores a locked object: COMPLIANCE retention header, versioned delete refused, never overwritten")
    void publishIsImmutable() {
        String bucket = bucket();
        S3ObjectLockAuditAnchorSink sink = sink(bucket, "COMPLIANCE");
        assertThat(failures()).isZero();
        Instant before = Instant.now();
        sink.publish(anchor("2026-10-07", 42, "ab".repeat(32)));

        var head = admin.headObject(b -> b.bucket(bucket).key("anchors/2026-10-07.json"));
        assertThat(head.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(head.objectLockRetainUntilDate()).isBetween(before.plus(Duration.ofDays(2)).minusSeconds(5),
                Instant.now().plus(Duration.ofDays(2)).plusSeconds(5));
        assertThat(head.versionId()).isNotNull();

        // a locked version cannot be deleted, not even by the account that wrote it
        assertThatThrownBy(() -> admin.deleteObject(b -> b.bucket(bucket).key("anchors/2026-10-07.json").versionId(head.versionId())))
                .isInstanceOf(S3Exception.class).satisfies(e -> assertThat(((S3Exception) e).statusCode()).isIn(400, 403));
        assertThat(admin.headObject(b -> b.bucket(bucket).key("anchors/2026-10-07.json").versionId(head.versionId())).objectLockMode())
                .isEqualTo(ObjectLockMode.COMPLIANCE);

        // a second publish for the same date (retry, or an attempt to substitute a different hash) leaves the first object intact
        sink.publish(anchor("2026-10-07", 99, "ee".repeat(32)));
        assertThat(sink.latest()).contains(anchor("2026-10-07", 42, "ab".repeat(32)));
        assertThat(failures()).isZero();
    }

    @Test
    @DisplayName("latest() reads back the newest anchor by date regardless of write order; empty bucket gives none")
    void latestReadsBackNewest() {
        String bucket = bucket();
        S3ObjectLockAuditAnchorSink sink = sink(bucket, "GOVERNANCE");
        assertThat(sink.latest()).isEmpty();
        sink.publish(anchor("2026-10-05", 5, "05".repeat(32)));
        sink.publish(anchor("2026-10-07", 7, "07".repeat(32)));
        sink.publish(anchor("2026-10-06", 6, "06".repeat(32)));
        assertThat(sink.latest()).contains(anchor("2026-10-07", 7, "07".repeat(32)));
        assertThat(admin.headObject(b -> b.bucket(bucket).key("anchors/2026-10-06.json")).objectLockMode())
                .isEqualTo(ObjectLockMode.GOVERNANCE);
    }

    @Test
    @DisplayName("a bucket WITHOUT Object Lock is flagged at startup and publishing with retention fails with an alert metric")
    void bucketWithoutObjectLockIsFlagged() {
        String bucket = bucket();
        admin.createBucket(b -> b.bucket(bucket));
        S3ObjectLockAuditAnchorSink sink = new S3ObjectLockAuditAnchorSink(TestMinio.client(minio), bucket, "anchors/",
                "COMPLIANCE", 2, false, Clock.systemUTC(), registry);
        sink.verifyBucket();
        assertThat(failures()).isEqualTo(1.0);
        assertThatThrownBy(() -> sink.publish(anchor("2026-10-07", 1, "ab".repeat(32)))).isInstanceOf(S3Exception.class);
        assertThat(failures()).isEqualTo(2.0);
        assertThat(sink.latest()).isEmpty();
    }
}
