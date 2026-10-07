package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditAnchor;
import de.makibytes.registerwerk.audit.api.AuditAnchorSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the daily signed tip anchor as an immutable object in an S3-compatible bucket with Object
 * Lock (T6-17), so the database operator cannot rewrite the anchor history together with the chain.
 * Selected with {@code registerwerk.audit.anchor-sink=s3}; the default stays NOOP (anchors only in
 * {@code audit_chain_anchor}).
 *
 * <p>One object per anchor date, {@code <prefix><yyyy-MM-dd>.json}, written with
 * {@code If-None-Match: *} (never overwrites) and a retain-until date in COMPLIANCE (default, nobody can
 * shorten it) or GOVERNANCE mode. {@link #latest()} lists the prefix and reads back the newest key, which
 * is what {@link AuditChainVerificationService} compares against the chain.
 *
 * <p>Failure handling: neither call ever blocks or fails an audit write (anchoring is a scheduled job
 * independent of the append path). A failed publish is counted in
 * {@code registerwerk_audit_anchor_sink_failures_total{operation="publish"}} and rethrown to the
 * anchor job, which keeps the local anchor and retries it hourly; a failed read counts under
 * {@code operation="read"} and yields {@code Optional.empty()} (alert on the counter).
 *
 * <p>Required bucket setup: Object Lock enabled at creation (it cannot be added later to a bucket that
 * was not created with it on most providers). {@code registerwerk.audit.anchor.s3.create-bucket=true}
 * creates a lock-enabled bucket for local demos; production buckets are provisioned out of band.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.audit.anchor-sink", havingValue = "s3")
class S3ObjectLockAuditAnchorSink implements AuditAnchorSink {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectLockAuditAnchorSink.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SUFFIX = ".json";

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final ObjectLockMode mode;
    private final int retentionDays;
    private final boolean createBucket;
    private final Clock clock;
    private final Counter publishFailures;
    private final Counter readFailures;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);

    @Autowired
    S3ObjectLockAuditAnchorSink(
            @Value("${registerwerk.audit.anchor.s3.bucket:}") String bucket,
            @Value("${registerwerk.audit.anchor.s3.prefix:audit-anchors/}") String prefix,
            @Value("${registerwerk.audit.anchor.s3.endpoint:}") String endpoint,
            @Value("${registerwerk.audit.anchor.s3.region:eu-central-1}") String region,
            @Value("${registerwerk.audit.anchor.s3.access-key:}") String accessKey,
            @Value("${registerwerk.audit.anchor.s3.secret-key:}") String secretKey,
            @Value("${registerwerk.audit.anchor.s3.path-style:false}") boolean pathStyle,
            @Value("${registerwerk.audit.anchor.s3.retention-mode:COMPLIANCE}") String retentionMode,
            @Value("${registerwerk.audit.anchor.s3.retention-days:3650}") int retentionDays,
            @Value("${registerwerk.audit.anchor.s3.create-bucket:false}") boolean createBucket,
            MeterRegistry registry) {
        this(buildClient(endpoint, region, accessKey, secretKey, pathStyle), bucket, prefix, retentionMode,
                retentionDays, createBucket, Clock.systemUTC(), registry);
    }

    S3ObjectLockAuditAnchorSink(S3Client s3, String bucket, String prefix, String retentionMode, int retentionDays,
                                boolean createBucket, Clock clock, MeterRegistry registry) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("registerwerk.audit.anchor-sink=s3 requires "
                    + "registerwerk.audit.anchor.s3.bucket (an S3 bucket created with Object Lock enabled).");
        }
        if (retentionDays < 1) {
            throw new IllegalStateException("registerwerk.audit.anchor.s3.retention-days must be >= 1");
        }
        this.s3 = s3;
        this.bucket = bucket.trim();
        String p = prefix == null ? "" : prefix.trim();
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
        this.mode = parseMode(retentionMode);
        this.retentionDays = retentionDays;
        this.createBucket = createBucket;
        this.clock = clock;
        this.publishFailures = Counter.builder("registerwerk_audit_anchor_sink_failures_total")
                .description("Failed audit-anchor writes/reads against the external S3 Object Lock sink")
                .tag("operation", "publish").register(registry);
        this.readFailures = Counter.builder("registerwerk_audit_anchor_sink_failures_total")
                .tag("operation", "read").register(registry);
        Gauge.builder("registerwerk_audit_anchor_sink_last_success_timestamp_seconds", lastSuccessEpochSeconds,
                AtomicLong::get).description("Unix time of the last anchor written to the external sink (0 = never)")
                .register(registry);
    }

    static ObjectLockMode parseMode(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        return switch (v) {
            case "COMPLIANCE" -> ObjectLockMode.COMPLIANCE;
            case "GOVERNANCE" -> ObjectLockMode.GOVERNANCE;
            default -> throw new IllegalStateException(
                    "registerwerk.audit.anchor.s3.retention-mode must be COMPLIANCE or GOVERNANCE, got '" + raw + "'");
        };
    }

    private static S3Client buildClient(String endpoint, String region, String accessKey, String secretKey,
                                        boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(StringUtils.hasText(region) ? region : "eu-central-1"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /** Creates the bucket (demo only) and warns loudly when Object Lock is not active on it. */
    @PostConstruct
    void verifyBucket() {
        try {
            if (createBucket) {
                try {
                    s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
                    log.info("Created Object Lock bucket '{}' for audit anchors", bucket);
                } catch (BucketAlreadyOwnedByYouException ignored) {
                    // already there
                }
            }
            var cfg = s3.getObjectLockConfiguration(b -> b.bucket(bucket)).objectLockConfiguration();
            if (cfg == null || cfg.objectLockEnabled() != ObjectLockEnabled.ENABLED) {
                publishFailures.increment();
                log.error("AUDIT ANCHOR: bucket '{}' does not have Object Lock enabled; anchors written there are "
                        + "NOT immutable and publishing with retention will be rejected. Create the bucket with "
                        + "Object Lock enabled.", bucket);
            }
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.error("AUDIT ANCHOR: cannot verify Object Lock on bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        s3.close();
    }

    String keyFor(LocalDate date) {
        return prefix + date + SUFFIX;
    }

    @Override
    public void publish(AuditAnchor anchor) {
        String key = keyFor(anchor.anchorDate());
        try {
            Instant retainUntil = clock.instant().plus(retentionDays, ChronoUnit.DAYS);
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType("application/json")
                            .objectLockMode(mode)
                            .objectLockRetainUntilDate(retainUntil)
                            .ifNoneMatch("*")
                            .build(),
                    RequestBody.fromString(toJson(anchor)));
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
            log.info("Audit anchor {} (sequence_no={}) written to s3://{}/{} ({} until {})", anchor.anchorDate(),
                    anchor.sequenceNo(), bucket, key, mode, retainUntil);
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                // already published for that date (retry after a partial failure): nothing to do
                lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
                log.info("Audit anchor {} already present in s3://{}/{}", anchor.anchorDate(), bucket, key);
                return;
            }
            publishFailures.increment();
            throw e;
        } catch (RuntimeException e) {
            publishFailures.increment();
            throw e;
        }
    }

    @Override
    public Optional<AuditAnchor> latest() {
        try {
            String newest = null;
            String token = null;
            do {
                ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).continuationToken(token).build());
                for (var o : page.contents()) {
                    if (o.key().endsWith(SUFFIX) && (newest == null || o.key().compareTo(newest) > 0)) {
                        newest = o.key();
                    }
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
            if (newest == null) {
                return Optional.empty();
            }
            String key = newest;
            byte[] body = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
            return Optional.of(fromJson(body));
        } catch (RuntimeException e) {
            readFailures.increment();
            log.error("AUDIT ANCHOR: reading the newest anchor from s3://{}/{} failed; the external anchor was NOT "
                    + "compared this run: {}", bucket, prefix, e.getMessage());
            return Optional.empty();
        }
    }

    static String toJson(AuditAnchor a) {
        var node = JSON.createObjectNode();
        node.put("schema", 1);
        node.put("anchorDate", a.anchorDate().toString());
        node.put("sequenceNo", a.sequenceNo());
        node.put("entryHash", a.entryHashHex());
        if (a.sigHex() == null) {
            node.putNull("sig");
        } else {
            node.put("sig", a.sigHex());
        }
        return JSON.writeValueAsString(node);
    }

    static AuditAnchor fromJson(byte[] body) {
        JsonNode n = JSON.readTree(body);
        JsonNode sig = n.get("sig");
        return new AuditAnchor(LocalDate.parse(n.get("anchorDate").asString()), n.get("sequenceNo").asLong(),
                n.get("entryHash").asString(), sig == null || sig.isNull() ? null : sig.asString());
    }
}
