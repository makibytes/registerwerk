package de.makibytes.registerwerk.regreporting.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Persists regulatory report XML to S3 and updates the regreport_submission record.
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
     * Stores the XML document to S3 and records the document key in the submission row.
     *
     * @return the S3 object key
     */
    String store(UUID submissionId, String reportType, String jurisdiction,
                 LocalDate periodEnd, byte[] xmlDocument) {
        String key = buildKey(reportType, jurisdiction, periodEnd, submissionId);
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getDocumentBucket())
                            .key(key)
                            .contentType("application/xml")
                            .build(),
                    RequestBody.fromBytes(xmlDocument));
            submissions.recordDocumentKey(submissionId, key);
            log.info("Stored regulatory report s3://{}/{}", props.getDocumentBucket(), key);
        } catch (Exception e) {
            log.error("Failed to store report to S3 for submissionId={}: {}", submissionId, e.getMessage());
        }
        return key;
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
