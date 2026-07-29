package de.makibytes.registerwerk.regreporting.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies Phase 5 finding #6: document_hash was defined in the schema but never populated. */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3DocumentStore unit tests")
class S3DocumentStoreTest {

    @Mock private S3Client s3;
    @Mock private ReportingProperties props;
    @Mock private RegReportSubmissions submissions;

    private S3DocumentStore store;

    @BeforeEach
    void setUp() {
        store = new S3DocumentStore(s3, props, submissions);
        when(props.getDocumentBucket()).thenReturn("test-bucket");
    }

    @Test
    @DisplayName("store computes and records the SHA-256 hash of the exact bytes uploaded")
    void store_recordsCorrectHash() throws Exception {
        byte[] xml = "<Document/>".getBytes(StandardCharsets.UTF_8);
        UUID submissionId = UUID.randomUUID();
        when(s3.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        store.store(submissionId, "DAC8_CARF", "DE_BAFIN", LocalDate.of(2026, 12, 31), xml);

        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(xml);
        ArgumentCaptor<byte[]> hashCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(submissions).recordDocumentHash(org.mockito.ArgumentMatchers.eq(submissionId), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("the hash is recorded even if the S3 upload itself fails")
    void store_recordsHashEvenOnUploadFailure() {
        byte[] xml = "<Document/>".getBytes(StandardCharsets.UTF_8);
        UUID submissionId = UUID.randomUUID();
        when(s3.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        store.store(submissionId, "DAC8_CARF", "DE_BAFIN", LocalDate.of(2026, 12, 31), xml);

        verify(submissions).recordDocumentHash(org.mockito.ArgumentMatchers.eq(submissionId), any(byte[].class));
    }
}
