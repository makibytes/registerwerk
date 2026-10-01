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

    // ── 6-34: public term sheet gating, determinism, post-issuance replacement ─────────────────────────

    private Asset assetWithStatus(UUID id, de.makibytes.registerwerk.asset.api.AssetStatus status) {
        Asset a = new Asset();
        ReflectionTestUtils.setField(a, "id", id);
        a.setStatus(status);
        return a;
    }

    private AssetDocument sheet(UUID assetId, String hash, java.time.Instant uploadedAt,
                                de.makibytes.registerwerk.asset.api.AssetDocumentSource source) {
        AssetDocument d = new AssetDocument();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(d, "uploadedAt", uploadedAt);
        d.setAssetId(assetId);
        d.setDocumentType(AssetDocumentType.TERM_SHEET);
        d.setSource(source);
        d.setContentHash(hash);
        d.setStorageRef("inline");
        return d;
    }

    @Test
    void publicTermSheetIsNotServedBeforeIssuance() {
        UUID assetId = UUID.randomUUID();
        for (var status : java.util.List.of(de.makibytes.registerwerk.asset.api.AssetStatus.DRAFT,
                de.makibytes.registerwerk.asset.api.AssetStatus.PENDING_APPROVAL,
                de.makibytes.registerwerk.asset.api.AssetStatus.APPROVED)) {
            assertThat(service().publicTermSheet(assetWithStatus(assetId, status))).isEmpty();
        }
    }

    @Test
    void publicTermSheetIsDeterministicEarliestUploadWhateverTheRowOrder() {
        UUID assetId = UUID.randomUUID();
        AssetDocument first = sheet(assetId, "aa", java.time.Instant.parse("2026-01-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.UPLOAD);
        AssetDocument later = sheet(assetId, "bb", java.time.Instant.parse("2026-02-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.UPLOAD);
        when(documentRepository.findByAssetIdAndDocumentTypeAndDeletedAtIsNull(assetId, AssetDocumentType.TERM_SHEET))
                .thenReturn(java.util.List.of(later, first), java.util.List.of(first, later));
        Asset issued = assetWithStatus(assetId, de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);

        var a = service().publicTermSheet(issued).orElseThrow();
        var b = service().publicTermSheet(issued).orElseThrow();

        assertThat(a.document()).isSameAs(first);
        assertThat(b.document()).isSameAs(first);
        assertThat(a.version()).isEqualTo(1);
    }

    @Test
    void publicTermSheetPrefersTheUploadMatchingTheOnChainAnchoredHash() {
        UUID assetId = UUID.randomUUID();
        AssetDocument reviewed = sheet(assetId, "aa", java.time.Instant.parse("2026-01-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.UPLOAD);
        AssetDocument anchoredUpload = sheet(assetId, "bb", java.time.Instant.parse("2026-02-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.UPLOAD);
        AssetDocument onchain = sheet(assetId, "0xBB", java.time.Instant.parse("2026-03-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.ONCHAIN_ERC1643);
        when(documentRepository.findByAssetIdAndDocumentTypeAndDeletedAtIsNull(assetId, AssetDocumentType.TERM_SHEET))
                .thenReturn(java.util.List.of(reviewed, onchain, anchoredUpload));

        var result = service().publicTermSheet(assetWithStatus(assetId,
                de.makibytes.registerwerk.asset.api.AssetStatus.SUSPENDED)).orElseThrow();

        assertThat(result.document()).isSameAs(anchoredUpload);
    }

    @Test
    void issuerUploadOfANewTermSheetAfterIssuanceIsRefused() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(
                assetWithStatus(assetId, de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED)));

        assertThatThrownBy(() -> service().uploadDocument(assetId, "x".getBytes(StandardCharsets.UTF_8), "t.pdf",
                "application/pdf", AssetDocumentType.TERM_SHEET, UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verify(documentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void amendmentKeepsAndSupersedesTheOldVersionAndNeedsAnApprover() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(
                assetWithStatus(assetId, de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED)));
        AssetDocument old = sheet(assetId, "aa", java.time.Instant.parse("2026-01-01T00:00:00Z"),
                de.makibytes.registerwerk.asset.api.AssetDocumentSource.UPLOAD);
        when(documentRepository.findByAssetIdAndDocumentTypeAndDeletedAtIsNull(assetId, AssetDocumentType.TERM_SHEET))
                .thenReturn(java.util.List.of(old));
        when(documentRepository.save(any())).thenAnswer(invocation -> {
            AssetDocument document = invocation.getArgument(0);
            if (document.getId() == null) ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
            return document;
        });

        assertThatThrownBy(() -> service().amendTermSheet(assetId, "v2".getBytes(StandardCharsets.UTF_8), "t2.pdf",
                "application/pdf", UUID.randomUUID(), null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        AssetDocument amended = service().amendTermSheet(assetId, "v2".getBytes(StandardCharsets.UTF_8), "t2.pdf",
                "application/pdf", UUID.randomUUID(), UUID.randomUUID());

        assertThat(old.getSupersededBy()).isEqualTo(amended.getId());
        assertThat(old.isDeleted()).isFalse();
        verify(events).publishEvent(any(de.makibytes.registerwerk.asset.events.TermSheetSupersededEvent.class));
    }
}
