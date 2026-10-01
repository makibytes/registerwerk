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
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;
import org.mockito.InOrder;
import org.mockito.Mockito;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
 * Verifies Travel Rule behaviour under Regulation (EU) 2023/1113 (TFR) and the 6-26/6-27/6-29 fixes:
 * await-then-submit delivery, complete payload, Art. 14(5) proof path, authenticated inbound dedup.
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

    @Mock
    private TravelRuleCompletionWriter writer;

    @Mock
    private WalletControlProofService proofService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TravelRuleProperties properties = new TravelRuleProperties();

    private final UUID assetId = UUID.randomUUID();
    private final Ivms101.TravelRuleMessage payload = new Ivms101.TravelRuleMessage(
            null, List.of(new Ivms101.Originator(identity("Issuer AG"), "0xfrom")), null,
            List.of(new Ivms101.Beneficiary(identity("Holder GmbH"), "0xto")), null);

    private static Ivms101.IdentityPayload identity(String name) {
        return new Ivms101.IdentityPayload(
                new Ivms101.Person(null, new Ivms101.LegalPerson(
                        List.of(new Ivms101.LegalPersonNameId(name, Ivms101.LegalPersonNameTypeCode.LEGL)), null)),
                new Ivms101.GeographicAddress(Ivms101.AddressTypeCode.BIZZ, List.of("Main St"), "1", null, "10115",
                        "Berlin", null, "DE"),
                null, null, null, null);
    }

    private TravelRuleService serviceWith(List<TravelRuleProtocolPort> protocols) {
        properties.getOwnVasp().setDid("did:example:registerwerk");
        properties.getOwnVasp().setLegalName("Registerwerk Operator GmbH");
        properties.setSendAttempts(1);
        properties.setSendTimeoutSeconds(1);
        TravelRuleService service = new TravelRuleService(
                protocols, jdbc, objectMapper, caspRegistry, eventPublisher, new SimpleMeterRegistry(),
                writer, proofService, properties, Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "selfHostedVerificationThresholdEur", new BigDecimal("1000"));
        return service;
    }

    private TravelRuleGate.TransferContext ctx(BigDecimal eur) {
        return new TravelRuleGate.TransferContext(assetId, "0xfrom", "0xto", eur, null, null,
                new BigDecimal("42"), "BND", "0xtoken");
    }

    private TravelRuleProtocolPort vaspResolvingProtocol() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.of(new TravelRuleProtocolPort.VaspInfo(
                "did:example:vasp1", "Other VASP AG", "DE", "https://vasp.example/trp", "529900T8BM49AURSDO55")));
        return protocol;
    }

    private List<String> insertedStatuses() {
        ArgumentCaptor<TravelRuleCompletionWriter.NewMessage> c =
                ArgumentCaptor.forClass(TravelRuleCompletionWriter.NewMessage.class);
        verify(writer, atLeastOnce()).insertOutbound(c.capture());
        return c.getAllValues().stream().map(TravelRuleCompletionWriter.NewMessage::status).toList();
    }

    @Test
    @DisplayName("CASP transfer: complete message (own VASP, resolved beneficiary VASP, transfer details) is sent to the resolved VASP")
    void vaspTransfer_sendsCompleteMessageToResolvedVasp() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        when(protocol.protocolName()).thenReturn("TRP");
        when(protocol.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture("proto-msg-1"));
        TravelRuleService service = serviceWith(List.of(protocol));

        boolean sent = service.checkAndSend(ctx(new BigDecimal("50")), payload);

        assertThat(sent).isTrue();
        ArgumentCaptor<Ivms101.TravelRuleMessage> msg = ArgumentCaptor.forClass(Ivms101.TravelRuleMessage.class);
        ArgumentCaptor<TravelRuleProtocolPort.VaspInfo> vasp = ArgumentCaptor.forClass(TravelRuleProtocolPort.VaspInfo.class);
        verify(protocol).send(any(UUID.class), msg.capture(), vasp.capture());
        assertThat(vasp.getValue().endpoint()).isEqualTo("https://vasp.example/trp");
        assertThat(msg.getValue().originatingVasp().originatingVasp().vaspId()).isEqualTo("did:example:registerwerk");
        assertThat(msg.getValue().beneficiaryVasp().beneficiaryVasp().vaspId()).isEqualTo("did:example:vasp1");
        assertThat(msg.getValue().transferDetails().instructedAmount()).isEqualTo("42");
        assertThat(msg.getValue().transferDetails().currencyOfTransfer()).isEqualTo("BND");
        verify(writer).markSent(any(), org.mockito.ArgumentMatchers.eq("TRP"), org.mockito.ArgumentMatchers.eq("proto-msg-1"), any());
    }

    @Test
    @DisplayName("the PENDING_SEND row is committed before the send and the result is recorded after (no markSent race)")
    void rowCommittedBeforeSend_resultAfter() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        when(protocol.protocolName()).thenReturn("TRP");
        when(protocol.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture("id"));
        TravelRuleService service = serviceWith(List.of(protocol));

        service.checkAndSend(ctx(null), payload);

        InOrder order = Mockito.inOrder(writer, protocol);
        order.verify(writer).insertOutbound(any());
        order.verify(protocol).send(any(), any(), any());
        order.verify(writer).markSent(any(), any(), any(), any());
        assertThat(insertedStatuses()).containsExactly(TravelRuleService.STATUS_PENDING_SEND);
    }

    @Test
    @DisplayName("delivery failure persists FAILED and refuses the operation (previously returned true and the tx was submitted)")
    void sendFailure_refusesOperation() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("connection refused"));
        when(protocol.send(any(), any(), any())).thenReturn(failed);
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("could not be delivered");

        verify(writer).markFailed(any(UUID.class), org.mockito.ArgumentMatchers.contains("connection refused"));
        verify(writer, never()).markSent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a send that never completes is bounded by the timeout and refuses the operation")
    void sendTimeout_refusesOperation() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        when(protocol.send(any(), any(), any())).thenReturn(new CompletableFuture<>());
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), payload))
                .isInstanceOf(ComplianceGateException.class);

        verify(writer).markFailed(any(UUID.class), org.mockito.ArgumentMatchers.contains("no acceptance"));
    }

    @Test
    @DisplayName("missing mandatory originator data -> INCOMPLETE_IVMS, nothing is sent")
    void incompleteOriginator_refusedWithoutSending() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        TravelRuleService service = serviceWith(List.of(protocol));
        Ivms101.TravelRuleMessage thin = new Ivms101.TravelRuleMessage(null,
                List.of(new Ivms101.Originator(null, "0xfrom")), null,
                List.of(new Ivms101.Beneficiary(identity("Holder GmbH"), "0xto")), null);

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), thin))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("originator.name");

        assertThat(insertedStatuses()).containsExactly(TravelRuleService.STATUS_INCOMPLETE_IVMS);
        verify(protocol, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("own VASP identity not configured -> refused (cannot build originatingVasp)")
    void ownVaspMissing_refused() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        TravelRuleService service = serviceWith(List.of(protocol));
        properties.getOwnVasp().setDid(null);

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("own VASP identity");
        verify(protocol, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("self-hosted beneficiary below EUR 1,000 - recorded, no verification flag")
    void selfHostedBelowThreshold_recorded() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        TravelRuleService service = serviceWith(List.of(protocol));

        boolean sent = service.checkAndSend(ctx(new BigDecimal("500")), payload);

        assertThat(sent).isFalse();
        assertThat(insertedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_RECORDED);
    }

    @Test
    @DisplayName("self-hosted beneficiary with unknown EUR value and no proof - blocked with an actionable message")
    void selfHostedUnknownValue_blocksWithoutProof() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("wallet-proofs");
        assertThat(insertedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_VERIFY_REQUIRED);
    }

    @Test
    @DisplayName("registered holder wallet with a verified control proof satisfies Art. 14(5) (previously permanently refused)")
    void selfHostedWithVerifiedProof_passes() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        UUID proofId = UUID.randomUUID();
        when(proofService.findValidProof(assetId, "0xto")).thenReturn(Optional.of(proofId));
        TravelRuleService service = serviceWith(List.of(protocol));

        boolean sent = service.checkAndSend(ctx(null), payload);

        assertThat(sent).isFalse();
        ArgumentCaptor<TravelRuleCompletionWriter.NewMessage> c =
                ArgumentCaptor.forClass(TravelRuleCompletionWriter.NewMessage.class);
        verify(writer).insertOutbound(c.capture());
        assertThat(c.getValue().status()).isEqualTo(TravelRuleService.STATUS_UNHOSTED_VERIFIED);
        assertThat(c.getValue().walletProofId()).isEqualTo(proofId);
    }

    @Test
    @DisplayName("register-internal exemption is off by default and only applies when switched on")
    void internalExemption_offByDefault() {
        TravelRuleProtocolPort protocol = mock(TravelRuleProtocolPort.class);
        when(protocol.lookupVasp(anyString())).thenReturn(Optional.empty());
        when(proofService.isRegisteredHolderWallet(assetId, "0xto")).thenReturn(true);
        TravelRuleService service = serviceWith(List.of(protocol));

        assertThatThrownBy(() -> service.checkAndSend(ctx(null), payload)).isInstanceOf(ComplianceGateException.class);

        properties.setRegisterInternalExempt(true);
        assertThat(service.checkAndSend(ctx(null), payload)).isFalse();
        assertThat(insertedStatuses()).contains(TravelRuleService.STATUS_INTERNAL_REGISTER_TRANSFER);
    }

    @Test
    @DisplayName("no protocol adapter and high value - blocked as an unverifiable self-hosted transfer")
    void noProtocols_highValueTransferIsBlocked() {
        TravelRuleService service = serviceWith(List.of());

        assertThatThrownBy(() -> service.checkAndSend(ctx(new BigDecimal("2000")), payload))
                .isInstanceOf(ComplianceGateException.class);
        assertThat(insertedStatuses()).contains(TravelRuleService.STATUS_UNHOSTED_VERIFY_REQUIRED);
    }

    @Test
    @DisplayName("counterparty blocked under MiCA - rejection recorded, transfer aborted")
    void micaBlockedCounterparty_recordsAndThrows() {
        TravelRuleProtocolPort protocol = vaspResolvingProtocol();
        TravelRuleService service = serviceWith(List.of(protocol));
        org.mockito.Mockito.doThrow(new ComplianceGateException("MiCA check: counterparty CASP blocked"))
                .when(caspRegistry).assertCounterpartyPermitted(any(TravelRuleProtocolPort.VaspInfo.class));

        assertThatThrownBy(() -> service.checkAndSend(ctx(new BigDecimal("10")), payload))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("MiCA");

        assertThat(insertedStatuses()).contains(TravelRuleService.STATUS_BLOCKED_MICA);
        verify(protocol, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("failed-messages gauge reflects a live count (alerting metrics)")
    void failedMessagesGauge_reflectsLiveCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any()))
                .thenReturn(2L);
        new TravelRuleService(List.of(), jdbc, objectMapper, caspRegistry, eventPublisher, registry,
                writer, proofService, properties, Clock.systemUTC());

        double value = registry.get("registerwerk_travelrule_failed_messages_recent_total").gauge().value();

        assertThat(value).isEqualTo(2.0);
    }

    // ── inbound ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inbound: payload claiming another VASP than the authenticated peer is rejected")
    void receiveInbound_rejectsMismatchedVaspIdentity() {
        TravelRuleService service = serviceWith(List.of());

        assertThatThrownBy(() -> service.receiveInbound("did:example:other", validInboundPayload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("inbound: stored under the authenticated peer with payload hash, details and event")
    void receiveInbound_persistsAndPublishesEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TravelRuleService service = serviceWith(List.of());

        service.receiveInbound("did:example:vasp1", validInboundPayload());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(anyString(), args.capture());
        assertThat(args.getAllValues().get(0)).contains("did:example:vasp1", "transfer-123", "0xfrom", "0xto");
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.travelrule.events.TravelRuleMessageReceivedEvent.class));
    }

    @Test
    @DisplayName("inbound: identical re-delivery is idempotent (no second event)")
    void receiveInbound_replayDoesNotPublishEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        TravelRuleService service = serviceWith(List.of());

        service.receiveInbound("did:example:vasp1", validInboundPayload());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("inbound: same reference with a different payload is flagged CONFLICT, not silently dropped")
    void receiveInbound_differentPayloadSameReference_isConflict() {
        // 1st update = INSERT (stored), 2nd update = flag the other rows CONFLICT (one hit)
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1, 1);
        TravelRuleService service = serviceWith(List.of());

        service.receiveInbound("did:example:vasp1", validInboundPayload());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(event.capture());
        assertThat(event.getAllValues()).anyMatch(e ->
                e instanceof de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent c
                        && c.eventType().equals("TRAVEL_RULE_INBOUND_CONFLICT"));
    }

    @Test
    @DisplayName("inbound: sender CASP blocked in the register -> audit row and 403")
    void receiveInbound_blockedSenderRejected() {
        TravelRuleService service = serviceWith(List.of());
        org.mockito.Mockito.doThrow(new ComplianceGateException("revoked"))
                .when(caspRegistry).assertInboundSenderPermitted(any());

        assertThatThrownBy(() -> service.receiveInbound("did:example:vasp1", validInboundPayload()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(writer).insertInboundRejection(any(), org.mockito.ArgumentMatchers.eq("did:example:vasp1"), any(), any(),
                any(), any(), any(), org.mockito.ArgumentMatchers.eq(TravelRuleService.STATUS_REJECTED_CASP), any());
    }

    private static Ivms101.TravelRuleMessage validInboundPayload() {
        return new Ivms101.TravelRuleMessage(
                new Ivms101.OriginatingVasp(new Ivms101.VaspIdentity("did:example:vasp1", "VASP One")),
                List.of(new Ivms101.Originator(identity("Sender AG"), "0xfrom")),
                new Ivms101.BeneficiaryVasp(new Ivms101.VaspIdentity("did:example:registerwerk", "Registerwerk")),
                List.of(new Ivms101.Beneficiary(identity("Holder GmbH"), "0xto")),
                new Ivms101.TransferDetails("transfer-123", "2026-08-08", "10", "EUR", "CRYPTO", null));
    }
}
