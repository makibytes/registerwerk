package de.makibytes.registerwerk.audit.internal;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Real MinIO (S3 API incl. Object Lock) for the audit-anchor sink tests. Keep the image tag in lockstep
 * with docker-compose.yml's {@code minio} service (profile {@code audit-anchor}). The official minio/minio
 * images are no longer published, so this pins the last Bitnami repack (MinIO RELEASE 2025-05-24).
 */
final class TestMinio {
    static final String IMAGE = "bitnamilegacy/minio:2025.5.24";
    static final String ACCESS_KEY = "minioadmin";
    static final String SECRET_KEY = "minioadmin-it-secret";

    private TestMinio() {
    }

    static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE)
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(new WaitAllStrategy()
                        .withStrategy(Wait.forHttp("/minio/health/ready").forPort(9000))
                        .withStrategy(new S3RoundTripWait())
                        .withStartupTimeout(Duration.ofSeconds(90)));
    }

    /**
     * {@code /minio/health/ready} can answer before the S3 API serves authenticated requests. The sink under test
     * creates its bucket exactly once, at context start, and only logs a failure; with a MinIO that was
     * "ready" but not yet serving S3 the bucket was never created and the test then failed with
     * {@code NoSuchBucket} (seen in CI as "The target server failed to respond"). Container start therefore
     * only completes once a real signed S3 call succeeds.
     */
    private static final class S3RoundTripWait extends AbstractWaitStrategy {
        @Override
        protected void waitUntilReady() {
            long deadline = System.nanoTime() + startupTimeout.toNanos();
            RuntimeException last = null;
            while (System.nanoTime() < deadline) {
                try (S3Client s3 = S3Client.builder().region(Region.of("us-east-1"))
                        .endpointOverride(URI.create("http://" + waitStrategyTarget.getHost() + ":"
                                + waitStrategyTarget.getMappedPort(9000)))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                        .build()) {
                    s3.listBuckets();
                    return;
                } catch (RuntimeException e) {
                    last = e;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for MinIO's S3 API", e);
                }
            }
            throw new IllegalStateException("MinIO's S3 API did not serve a signed request within " + startupTimeout, last);
        }
    }

    static String endpoint(GenericContainer<?> c) {
        return "http://" + c.getHost() + ":" + c.getMappedPort(9000);
    }

    static S3Client client(GenericContainer<?> c) {
        return S3Client.builder().region(Region.of("us-east-1"))
                .endpointOverride(URI.create(endpoint(c)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }
}
