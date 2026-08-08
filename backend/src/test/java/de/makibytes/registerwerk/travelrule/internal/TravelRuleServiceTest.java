package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Travel Rule behaviour under Regulation (EU) 2023/1113 (TFR):
 * — no de minimis threshold for CASP-to-CASP information transmission,
 * — EUR 1,000 threshold applies only to self-hosted address verification (Art. 14(5)),
 * — fail-closed when a VASP counterpart exists but no protocol adapter can deliver.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TravelRuleService TFR compliance unit tests")
class TravelRuleServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private CaspRegistryService caspRegistry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID assetId = UUID.randomUUID();
    private final Ivms101.TravelRuleMessage payload = new Ivms101.TravelRuleMessage(null, null, null, null, null);

    private TravelRuleService serviceWith(List<TravelRuleProtocolPort> protocols) {
        TravelRuleService service = new TravelRuleService(
                protocols, jdbc, objectMapper, caspRegistry, eventPublisher, new SimpleMeterRegistry(),
                new TravelRuleCompletionWriter(jdbc, eventPublisher));
        ReflectionTestUtils.setField(service, "selfHostedVerificationThresholdEur", new BigDecimal("1000"));
        return service;
    }

    private TravelRuleProtocolPort vaspResolvingProtocol() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.of(
                new TravelRuleProtocolPort.VaspInfo("did:example:vasp1", "Other VASP AG", "DE", "https://vasp.example")));
        return protocol;
    }

    @Test
    @DisplayName("low-value transfer to a VASP still dispatches IVMS-101 — no de minimis under TFR")
    void lowValueVaspTransfer_dispatchesMessage() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        when(protocol.send(any(), any())).thenReturn(CompletableFuture.completedFuture("proto-msg-1"));
        TravelRuleService service = serviceWith(List.of(protocol));

        boolean sent = service.checkAndSend(assetId, "0xfrom", "0xto", new BigDecimal("50"), payload);

        assertThat(sent).isTrue();
        verify(protocol).send(any(UUID.class), any());
    }

    @Test
    @DisplayName("self-hosted beneficiary below EUR 1,000 — recorded, no verification flag")
    void selfHostedBelowThreshold_recorded() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        TravelRuleService service = serviceWith(List.of(protocol));

        boolean sent = service.checkAndSend(assetId, "0xfrom", "0xunhosted", new BigDecimal("500"), payload);

        assertThat(sent).isFalse();
        assertThat(capturedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_RECORDED);
    }

    @Test
    @DisplayName("self-hosted beneficiary above EUR 1,000 — blocked until Art. 14(5) verification")
    void selfHostedAboveThreshold_blocksUntilVerified() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(
                assetId, "0xfrom", "0xunhosted", new BigDecimal("1500.00"), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("must be verified");
        assertThat(capturedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_VERIFY_REQUIRED);
    }

    @Test
    @DisplayName("self-hosted beneficiary with unknown EUR value — verification required (fail closed)")
    void selfHostedUnknownValue_blocksUntilVerified() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(assetId, "0xfrom", "0xunhosted", null, payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("must be verified");
        assertThat(capturedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_VERIFY_REQUIRED);
    }

    @Test
    @DisplayName("no protocol adapter and high value — blocked as an unverifiable self-hosted transfer")
    void noProtocols_highValueTransferIsBlocked() {
        TravelRuleService service = serviceWith(List.of());

        assertThatThrownBy(() -> service.checkAndSend(
                assetId, "0xfrom", "0xto", new BigDecimal("2000"), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("must be verified");
        assertThat(capturedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_VERIFY_REQUIRED);
    }

    @Test
    @DisplayName("protocol send failure marks the same message row FAILED (status update targets correct id)")
    void sendFailure_updatesSameRow() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("connection refused"));
        when(protocol.send(any(), any())).thenReturn(failed);
        TravelRuleService service = serviceWith(List.of(protocol));

        service.checkAndSend(assetId, "0xfrom", "0xto", new BigDecimal("10"), payload);

        // The UPDATE must reference the same UUID that was inserted as PENDING_SEND.
        ArgumentCaptor<Object[]> insertArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(anyString(), insertArgs.capture());
        List<Object[]> calls = insertArgs.getAllValues();
        UUID insertedId = (UUID) calls.get(0)[0];
        boolean updateTargetsInsertedRow = calls.stream()
                .skip(1)
                .anyMatch(args -> List.of(args).contains(insertedId));
        assertThat(updateTargetsInsertedRow)
                .as("status UPDATE must reference the inserted message id")
                .isTrue();
    }

    private List<String> capturedStatuses() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(anyString(), captor.capture());
        return captor.getAllValues().stream()
                .flatMap(args -> java.util.Arrays.stream(args))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    @Test
    @DisplayName("counterparty blocked under MiCA — rejection recorded, transfer aborted")
    void micaBlockedCounterparty_recordsAndThrows() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        TravelRuleService service = serviceWith(List.of(protocol));
        org.mockito.Mockito.doThrow(new ComplianceGateException("MiCA check: counterparty CASP blocked"))
                .when(caspRegistry).assertCounterpartyPermitted("did:example:vasp1");

        assertThatThrownBy(() -> service.checkAndSend(assetId, "0xfrom", "0xto", new BigDecimal("10"), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("MiCA");

        // The blocked attempt must appear in the message log (audit trail).
        assertThat(capturedStatuses()).contains(TravelRuleService.STATUS_BLOCKED_MICA);
        verify(protocol, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    @DisplayName("failed-messages gauge reflects a live count (repo-wide alerting follow-up)")
    void failedMessagesGauge_reflectsLiveCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any()))
                .thenReturn(2L);
        new TravelRuleService(List.of(), jdbc, objectMapper, caspRegistry, eventPublisher, registry,
                new TravelRuleCompletionWriter(jdbc, eventPublisher));

        double value = registry.get("registerwerk_travelrule_failed_messages_recent_total").gauge().value();

        assertThat(value).isEqualTo(2.0);
    }

    @Test
    @DisplayName("inbound messages require a VASP identity that matches the authenticated header")
    void receiveInbound_rejectsMismatchedVaspIdentity() {
        TravelRuleService service = serviceWith(List.of());

        assertThatThrownBy(() -> service.receiveInbound("did:example:other", validInboundPayload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("inbound messages are persisted with a stable replay-protection reference")
    void receiveInbound_persistsTransferReferenceAndPublishesEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TravelRuleService service = serviceWith(List.of());

        service.receiveInbound("did:example:vasp1", validInboundPayload());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).contains("did:example:vasp1", "transfer-123", "0xfrom", "0xto");
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.travelrule.events.TravelRuleMessageReceivedEvent.class));
    }

    @Test
    @DisplayName("replayed inbound messages do not publish duplicate audit events")
    void receiveInbound_replayDoesNotPublishEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        TravelRuleService service = serviceWith(List.of());

        service.receiveInbound("did:example:vasp1", validInboundPayload());

        verify(eventPublisher, never()).publishEvent(any());
    }

    private static Ivms101.TravelRuleMessage validInboundPayload() {
        return new Ivms101.TravelRuleMessage(
                new Ivms101.OriginatingVasp(new Ivms101.VaspIdentity("did:example:vasp1", "VASP One")),
                List.of(new Ivms101.Originator(null, "0xfrom")),
                new Ivms101.BeneficiaryVasp(new Ivms101.VaspIdentity("did:example:registerwerk", "Registerwerk")),
                List.of(new Ivms101.Beneficiary(null, "0xto")),
                new Ivms101.TransferDetails("transfer-123", "2026-08-08", "10", "EUR", "CRYPTO"));
    }
}
