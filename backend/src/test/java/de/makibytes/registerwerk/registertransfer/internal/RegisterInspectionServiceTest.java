package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import de.makibytes.registerwerk.registertransfer.api.InspectionStatus;
import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequest;
import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequestRepository;
import de.makibytes.registerwerk.registertransfer.events.RegisterInspectionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for §10 eWpG register inspection requests. The core legal rule is
 * the §10(2) eWpRV auto-approval split: Berechtigte (ISSUER/HOLDER/BENEFICIARY)
 * are approved automatically, everyone else waits for operator review.
 */
@ExtendWith(MockitoExtension.class)
class RegisterInspectionServiceTest {

    @Mock private RegisterInspectionRequestRepository requestRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private RegisterExtractRenderer extractRenderer;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private de.makibytes.registerwerk.finality.api.FinalityGate finalityGate;

    private RegisterInspectionService service;

    private static final UUID ASSET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RegisterInspectionService(requestRepository, assetRepository, holderRepository, extractRenderer, eventPublisher, finalityGate);
        lenient().when(requestRepository.save(any(RegisterInspectionRequest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── submit — §10(2) eWpRV auto-approval split ────────────────────────────

    @ParameterizedTest
    @EnumSource(value = InspectionLegalBasis.class, names = {"ISSUER", "HOLDER", "BENEFICIARY"})
    void submit_autoApprovesBerechtigte(InspectionLegalBasis basis) {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(new Asset()));

        RegisterInspectionRequest request = service.submit(
                ASSET_ID, UUID.randomUUID(), "Jane Doe", "jane@example.com", basis, null);

        assertThat(request.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        assertThat(request.getDecidedAt()).isNotNull();
        assertThat(request.getDecisionReason()).contains("Berechtigter");
    }

    @Test
    void submit_leavesLegitimateInterestPendingOperatorReview() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(new Asset()));

        RegisterInspectionRequest request = service.submit(
                ASSET_ID, UUID.randomUUID(), "Curious Journalist", "j@example.com",
                InspectionLegalBasis.LEGITIMATE_INTEREST, "Public interest research");

        assertThat(request.getStatus()).isEqualTo(InspectionStatus.REQUESTED);
        assertThat(request.getDecidedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any(RegisterInspectionEvent.class));
    }

    @Test
    void submit_autoApprovalPublishesAuditEvent() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(new Asset()));

        service.submit(ASSET_ID, UUID.randomUUID(), "Jane Doe", "jane@example.com",
                InspectionLegalBasis.HOLDER, null);

        ArgumentCaptor<RegisterInspectionEvent> captor = ArgumentCaptor.forClass(RegisterInspectionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo("AUTO_APPROVED");
        assertThat(captor.getValue().assetId()).isEqualTo(ASSET_ID);
    }

    @Test
    void submit_rejectsUnknownAsset() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
                ASSET_ID, UUID.randomUUID(), "X", "x@example.com", InspectionLegalBasis.HOLDER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── approve / reject ──────────────────────────────────────────────────────

    @Test
    void approve_transitionsRequestedToApproved() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setStatus(InspectionStatus.REQUESTED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        UUID operatorId = UUID.randomUUID();
        RegisterInspectionRequest result = service.approve(requestId, operatorId, "legitimate interest confirmed");

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        assertThat(result.getDecidedAt()).isNotNull();

        ArgumentCaptor<RegisterInspectionEvent> captor = ArgumentCaptor.forClass(RegisterInspectionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo("APPROVED");
        assertThat(captor.getValue().actorId()).isEqualTo(operatorId);
    }

    @Test
    void approve_rejectsANonRequestedRequest() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setStatus(InspectionStatus.APPROVED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approve(requestId, UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reject_transitionsRequestedToRejected() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setStatus(InspectionStatus.REQUESTED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        RegisterInspectionRequest result = service.reject(requestId, UUID.randomUUID(), "no legitimate interest shown");

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.REJECTED);

        ArgumentCaptor<RegisterInspectionEvent> captor = ArgumentCaptor.forClass(RegisterInspectionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo("REJECTED");
    }

    @Test
    void reject_rejectsANonRequestedRequest() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setStatus(InspectionStatus.FULFILLED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(requestId, UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── fulfil ────────────────────────────────────────────────────────────────

    @Test
    void fulfil_rendersExtractAndRecordsHash() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setAssetId(ASSET_ID);
        request.setStatus(InspectionStatus.APPROVED);
        request.setLegalBasis(InspectionLegalBasis.HOLDER);
        request.setRequesterName("Jane Doe");
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(new Asset()));
        Page<de.makibytes.registerwerk.deployment.api.AssetHolder> holders = new PageImpl<>(List.of());
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any())).thenReturn(holders);
        when(extractRenderer.render(any(), any(), eq(InspectionLegalBasis.HOLDER), eq("Jane Doe")))
                .thenReturn("pdf-bytes".getBytes());

        byte[] pdf = service.fulfil(requestId);

        assertThat(pdf).isEqualTo("pdf-bytes".getBytes());
        assertThat(request.getStatus()).isEqualTo(InspectionStatus.FULFILLED);
        assertThat(request.getContentHash()).startsWith("0x");
        assertThat(request.getFulfilledAt()).isNotNull();
        verify(finalityGate).require(de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_INSPECTION_FULFIL,
                ASSET_ID, null, de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED);
    }

    @Test
    void fulfil_blockedByFinalityGate_propagatesAndNeverTransitionsToFulfilled() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setAssetId(ASSET_ID);
        request.setStatus(InspectionStatus.APPROVED);
        request.setLegalBasis(InspectionLegalBasis.HOLDER);
        request.setRequesterName("Jane Doe");
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(new Asset()));
        org.mockito.Mockito.doThrow(new de.makibytes.registerwerk.finality.api.FinalityNotReachedException(
                        new de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked(
                                de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_INSPECTION_FULFIL,
                                ASSET_ID, de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED,
                                de.makibytes.registerwerk.finality.api.FinalityLevel.SAFE,
                                de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked.Reason.BELOW_REQUIRED,
                                "not yet final")))
                .when(finalityGate).require(any(), any(), any(), any());

        assertThatThrownBy(() -> service.fulfil(requestId))
                .isInstanceOf(de.makibytes.registerwerk.finality.api.FinalityNotReachedException.class);

        assertThat(request.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        verifyNoInteractions(extractRenderer);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void fulfil_rejectsANonApprovedRequest() {
        UUID requestId = UUID.randomUUID();
        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setStatus(InspectionStatus.REQUESTED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.fulfil(requestId))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(extractRenderer);
    }

    @Test
    void anyOperation_rejectsAnUnknownRequestId() {
        UUID requestId = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(requestId, UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown inspection request");
    }
}
