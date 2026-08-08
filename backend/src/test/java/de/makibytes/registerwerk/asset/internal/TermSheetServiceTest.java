package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentContentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.kyc.api.S3DocumentStorageAdapter;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TermSheetServiceTest {

    @Mock AssetDocumentRepository documentRepository;
    @Mock AssetDocumentContentRepository contentRepository;
    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock S3DocumentStorageAdapter s3;
    @Mock TermSheetOnChainFetchService onChainFetchService;
    @Mock ApplicationEventPublisher events;
    @Mock AssetRepository assetRepository;

    private TermSheetService service() {
        return new TermSheetService(documentRepository, contentRepository, deploymentRepository,
                s3, onChainFetchService, events, assetRepository);
    }

    @Test
    void uploadSanitizesClientSuppliedFileName() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(new Asset()));
        when(documentRepository.save(any())).thenAnswer(invocation -> {
            AssetDocument document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
            return document;
        });

        AssetDocument result = service().uploadDocument(assetId,
                "terms".getBytes(StandardCharsets.UTF_8), "../../bad\r\nname.pdf",
                "application/pdf", AssetDocumentType.TERM_SHEET, UUID.randomUUID());

        assertThat(result.getFileName()).isEqualTo("badname.pdf");
    }

    @Test
    void nestedDocumentLookupCannotEscapeItsAsset() {
        UUID assetId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndAssetIdAndDeletedAtIsNull(documentId, assetId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getDocument(assetId, documentId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void s3ObjectIsRemovedWhenDatabaseTransactionRollsBack() {
        TermSheetService service = service();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadS3("termsheets/key.pdf", new byte[] {1}, "application/pdf");
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(s3).delete("termsheets/key.pdf");
    }
}
