package de.makibytes.registerwerk.audit.internal;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

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
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
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
