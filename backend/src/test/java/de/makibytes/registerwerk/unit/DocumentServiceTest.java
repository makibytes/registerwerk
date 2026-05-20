package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.kyc.internal.DocumentService;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentContent;
import de.makibytes.registerwerk.kyc.api.KycDocumentContentRepository;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.S3DocumentStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService unit tests")
class DocumentServiceTest {

    @Mock
    private KycDocumentRepository kycDocumentRepository;

    @Mock
    private KycDocumentContentRepository kycDocumentContentRepository;

    @Mock
    private S3DocumentStorageAdapter s3DocumentStorageAdapter;

    @InjectMocks
    private DocumentService documentService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final int FOUR_MB  = 4 * 1024 * 1024;
    private static final int SIX_MB   = 6 * 1024 * 1024;

    private byte[] generateBytes(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'X');
        return bytes;
    }

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private KycDocument savedDocWith(UUID id, String storageRef) {
        KycDocument doc = new KycDocument();
        doc.setId(id);
        doc.setStorageRef(storageRef);
        doc.setMimeType("application/pdf");
        doc.setFileName("test.pdf");
        return doc;
    }

    // ── storeDocument ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("storeDocument should store content inline when file is below 5 MB threshold (4 MB)")
    void storeDocument_shouldStoreInlineWhenBelowThreshold() {
        byte[] content = generateBytes(FOUR_MB);
        UUID entityId = UUID.randomUUID();
        UUID uploadedBy = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        when(kycDocumentRepository.save(any())).thenAnswer(inv -> {
            KycDocument d = inv.getArgument(0);
            d.setId(docId);
            return d;
        });
        when(kycDocumentContentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycDocument result = documentService.storeDocument(
            entityId, content, "report.pdf", "application/pdf",
            KycDocument.DocumentType.ANNUAL_REPORT, uploadedBy);

        assertThat(result.getStorageRef()).isEqualTo("inline");
        verify(kycDocumentContentRepository).save(any(KycDocumentContent.class));
        verify(s3DocumentStorageAdapter, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("storeDocument should upload to S3 when file exceeds 5 MB threshold (6 MB)")
    void storeDocument_shouldUploadToS3WhenAboveThreshold() {
        byte[] content = generateBytes(SIX_MB);
        UUID entityId = UUID.randomUUID();
        UUID uploadedBy = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        when(kycDocumentRepository.save(any())).thenAnswer(inv -> {
            KycDocument d = inv.getArgument(0);
            d.setId(docId);
            return d;
        });

        KycDocument result = documentService.storeDocument(
            entityId, content, "large.pdf", "application/pdf",
            KycDocument.DocumentType.COMMERCIAL_REGISTER, uploadedBy);

        assertThat(result.getStorageRef()).isNotEqualTo("inline").contains("kyc/");
        verify(s3DocumentStorageAdapter).upload(any(), eq(content), eq("application/pdf"));
        verify(kycDocumentContentRepository, never()).save(any());
    }

    @Test
    @DisplayName("storeDocument should compute and set the SHA-256 hash of the content")
    void storeDocument_shouldComputeSha256Hash() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String expectedHash = sha256Hex(content);
        UUID entityId = UUID.randomUUID();

        when(kycDocumentRepository.save(any())).thenAnswer(inv -> {
            KycDocument d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(kycDocumentContentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycDocument result = documentService.storeDocument(
            entityId, content, "small.txt", "text/plain",
            KycDocument.DocumentType.OTHER, UUID.randomUUID());

        assertThat(result.getContentHash()).isEqualTo(expectedHash);
    }

    // ── retrieveContent ───────────────────────────────────────────────────────

    @Test
    @DisplayName("retrieveContent should fetch bytes from inline store when storageRef is 'inline'")
    void retrieveContent_shouldFetchFromInlineStore() {
        UUID docId = UUID.randomUUID();
        byte[] expected = "inline content".getBytes(StandardCharsets.UTF_8);

        KycDocument doc = savedDocWith(docId, "inline");
        KycDocumentContent contentEntity = new KycDocumentContent();
        contentEntity.setId(docId);
        contentEntity.setContent(expected);

        when(kycDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(kycDocumentContentRepository.findById(docId)).thenReturn(Optional.of(contentEntity));

        byte[] result = documentService.retrieveContent(docId);

        assertThat(result).isEqualTo(expected);
        verify(s3DocumentStorageAdapter, never()).download(any());
    }

    @Test
    @DisplayName("retrieveContent should delegate to S3 when storageRef is not 'inline'")
    void retrieveContent_shouldFetchFromS3WhenStorageRefNotInline() {
        UUID docId = UUID.randomUUID();
        String s3Key = "kyc/entity-id/uuid/file.pdf";
        byte[] s3Content = "s3 bytes".getBytes(StandardCharsets.UTF_8);

        KycDocument doc = savedDocWith(docId, s3Key);
        when(kycDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(s3DocumentStorageAdapter.download(s3Key)).thenReturn(s3Content);

        byte[] result = documentService.retrieveContent(docId);

        assertThat(result).isEqualTo(s3Content);
        verify(s3DocumentStorageAdapter).download(s3Key);
        verify(kycDocumentContentRepository, never()).findById(any());
    }

    // ── softDeleteDocument ────────────────────────────────────────────────────

    @Test
    @DisplayName("softDeleteDocument should set deletedAt on the document record")
    void softDeleteDocument_shouldSetDeletedAt() {
        UUID docId = UUID.randomUUID();
        KycDocument doc = savedDocWith(docId, "inline");

        when(kycDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(kycDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        documentService.softDeleteDocument(docId, UUID.randomUUID());

        assertThat(doc.getDeletedAt()).isNotNull();
        assertThat(doc.isDeleted()).isTrue();
        verify(kycDocumentRepository).save(doc);
    }
}
