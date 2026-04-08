package de.makibytes.registerwerk.infrastructure.persistence.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Adapter that wraps AWS SDK v2 S3Client for document storage operations.
 */
@Component
public class S3DocumentStorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageAdapter.class);

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    public S3DocumentStorageAdapter(S3Client s3Client, S3Properties s3Properties) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
    }

    /**
     * Uploads content to S3 under the given key.
     *
     * @param key      S3 object key (path within bucket)
     * @param content  raw bytes to store
     * @param mimeType MIME type for the Content-Type header
     */
    public void upload(String key, byte[] content, String mimeType) {
        log.debug("Uploading document to S3: bucket={}, key={}, size={}", s3Properties.getBucket(), key, content.length);
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(s3Properties.getBucket())
            .key(key)
            .contentType(mimeType)
            .contentLength((long) content.length)
            .build();
        s3Client.putObject(request, RequestBody.fromBytes(content));
        log.info("Uploaded document to S3: key={}", key);
    }

    /**
     * Downloads content from S3 for the given key.
     *
     * @param key S3 object key
     * @return raw bytes of the stored object
     */
    public byte[] download(String key) {
        log.debug("Downloading document from S3: bucket={}, key={}", s3Properties.getBucket(), key);
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(s3Properties.getBucket())
            .key(key)
            .build();
        return s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
    }

    /**
     * Deletes the object at the given key from S3.
     *
     * @param key S3 object key
     */
    public void delete(String key) {
        log.debug("Deleting document from S3: bucket={}, key={}", s3Properties.getBucket(), key);
        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(s3Properties.getBucket())
            .key(key)
            .build();
        s3Client.deleteObject(request);
        log.info("Deleted document from S3: key={}", key);
    }
}
