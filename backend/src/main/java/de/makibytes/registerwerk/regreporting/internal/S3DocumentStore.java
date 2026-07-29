package de.makibytes.registerwerk.regreporting.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persists draft/unvalidated reporting XML to S3 and updates its local export record.
 */
@Component
class S3DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStore.class);

    private final S3Client s3;
    private final ReportingProperties props;
    private final RegReportSubmissions submissions;

    S3DocumentStore(S3Client s3, ReportingProperties props, RegReportSubmissions submissions) {
        this.s3 = s3;
        this.props = props;
        this.submissions = submissions;
    }

    /**
     * Stores the draft XML document to S3 and records the object key in the export row.
     *
     * @return the S3 object key
     */
    String store(UUID submissionId, String reportType, String jurisdiction,
                 LocalDate periodEnd, byte[] xmlDocument) {
        String key = buildKey(reportType, jurisdiction, periodEnd, submissionId);
        // Hashed before the upload attempt — proves what was generated even if the S3 write
        // below fails; the alternative (hashing only on success) would leave no evidentiary
        // trail for the one case (an upload failure) where it matters most.
        submissions.recordDocumentHash(submissionId, sha256(xmlDocument));
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getDocumentBucket())
                            .key(key)
                            .contentType("application/xml")
                            .build(),
                    RequestBody.fromBytes(xmlDocument));
            submissions.recordDocumentKey(submissionId, key);
            log.info("Stored draft/unvalidated reporting document s3://{}/{}", props.getDocumentBucket(), key);
        } catch (Exception e) {
            log.error("Failed to store draft document in S3 for exportId={}: {}", submissionId, e.getMessage());
        }
        return key;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String buildKey(String reportType, String jurisdiction,
                                    LocalDate periodEnd, UUID submissionId) {
        return String.format("reports/%s/%s/%s/%s.xml",
                reportType.toLowerCase(),
                jurisdiction,
                periodEnd.getYear(),
                submissionId);
    }
}
