package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import de.makibytes.registerwerk.travelrule.events.TravelRuleMessageReceivedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates Travel Rule checks under Regulation (EU) 2023/1113 ("TFR") for
 * crypto-asset transfers, applicable since 30 December 2024.
 *
 * <p><strong>No de minimis threshold:</strong> per TFR Art. 14–16 and the EBA Travel
 * Rule Guidelines (EBA/GL/2024/11), originator and beneficiary information must
 * accompany <em>every</em> CASP-to-CASP crypto-asset transfer regardless of amount —
 * unlike fiat wire transfers. The EUR 1,000 threshold exists only for transfers
 * to/from self-hosted addresses: above it, Art. 14(5) requires the originating CASP
 * to verify that the self-hosted address is owned or controlled by its customer.
 *
 * <p>Trigger points (called by blockchain admin controller before forceTransfer/mint):
 * <ol>
 *   <li>Look up beneficiary wallet in VASP directory → if a VASP is found, send IVMS-101
 *       irrespective of the transfer amount</li>
 *   <li>Store outbound message + await ACK (async)</li>
 *   <li>For inbound: handle messages received at /api/v1/public/travel-rule/inbox</li>
 * </ol>
 *
 * <p><strong>Fail-closed:</strong> if the beneficiary is a VASP but no Travel Rule
 * protocol adapter is configured, the transfer is rejected — executing it without the
 * legally required information would breach TFR Art. 14.
 */
@Service
public class TravelRuleService {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleService.class);

    static final String STATUS_PENDING_SEND = "PENDING_SEND";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_RECEIVED = "RECEIVED";
    /** Self-hosted beneficiary, amount ≤ EUR 1,000 — info recorded, no counterpart CASP. */
    static final String STATUS_UNHOSTED_RECORDED = "UNHOSTED_RECORDED";
    /** Self-hosted beneficiary, amount > EUR 1,000 — Art. 14(5) ownership verification due. */
    static final String STATUS_UNHOSTED_VERIFY_REQUIRED = "UNHOSTED_VERIFY_REQUIRED";
    /** Counterparty CASP lacks MiCA authorization — transfer rejected (Reg (EU) 2023/1114). */
    static final String STATUS_BLOCKED_MICA = "BLOCKED_MICA";

    /** Art. 14(5) TFR self-hosted address verification threshold (not a messaging threshold). */
    @Value("${registerwerk.travel-rule.self-hosted-verification-threshold-eur:1000}")
    private BigDecimal selfHostedVerificationThresholdEur;

    private final List<TravelRuleProtocolPort> protocols;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CaspRegistryService caspRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TravelRuleCompletionWriter completionWriter;

    TravelRuleService(List<TravelRuleProtocolPort> protocols, JdbcTemplate jdbc,
                      ObjectMapper objectMapper, CaspRegistryService caspRegistry,
                      ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                      TravelRuleCompletionWriter completionWriter) {
        this.protocols = protocols;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.caspRegistry = caspRegistry;
        this.eventPublisher = eventPublisher;
        this.completionWriter = completionWriter;

        // Live-queried at scrape time — send() sets STATUS_FAILED on the async completion
        // callback's failure path, but nothing ever counted it (repo-wide alerting-gap follow-up).
        Gauge.builder("registerwerk_travelrule_failed_messages_recent_total", jdbc, TravelRuleService::countRecentFailures)
                .description("Count of travel_rule_message rows with status=FAILED in the last 24h")
                .register(meterRegistry);
    }

    /**
     * Performs the Travel Rule check for an outbound transfer and dispatches the
     * IVMS-101 message when the beneficiary is a VASP.
     *
     * @return true if a message was dispatched to a beneficiary VASP; false if the
     *         beneficiary address is self-hosted (recorded locally instead)
     * @throws IllegalStateException if the beneficiary is a VASP but no Travel Rule
     *         protocol adapter is configured (transfer must not proceed)
     */
    @Transactional(noRollbackFor = ComplianceGateException.class)
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet,
                                BigDecimal amountEur, Ivms101.TravelRuleMessage payload) {
        return checkAndSend(assetId, fromWallet, toWallet, amountEur, null, null, payload);
    }

    /**
     * Same as {@link #checkAndSend(UUID, String, String, BigDecimal, Ivms101.TravelRuleMessage)},
     * plus the transfer's real amount in its own native denomination when no EUR-equivalent
     * valuation is available (e.g. a decrypted confidential-transfer amount).
     * {@code nativeAmount}/{@code nativeSymbol} are only ever used as a fallback for the
     * persisted record when {@code amountEur} is null; they never affect the Art. 14(5)
     * self-hosted-verification threshold check, which stays EUR-denominated and — absent a real
     * EUR value — conservatively fails closed exactly as before.
     */
    @Transactional(noRollbackFor = ComplianceGateException.class)
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet, BigDecimal amountEur,
                                BigDecimal nativeAmount, String nativeSymbol, Ivms101.TravelRuleMessage payload) {
        Optional<TravelRuleProtocolPort.VaspInfo> vasp = resolveVasp(toWallet);
        if (vasp.isEmpty()) {
            // Self-hosted (unhosted) beneficiary: no counterpart CASP to message.
            // Unknown EUR value is treated conservatively as above the Art. 14(5)
            // threshold (verification required) — fail closed.
            boolean verificationRequired = amountEur == null
                    || amountEur.compareTo(selfHostedVerificationThresholdEur) > 0;
            String status = verificationRequired ? STATUS_UNHOSTED_VERIFY_REQUIRED : STATUS_UNHOSTED_RECORDED;
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur, nativeAmount, nativeSymbol,
                    "OUTBOUND", status, null, null, payload);
            if (verificationRequired) {
                log.warn("Travel Rule: transfer of {} EUR to self-hosted wallet {} exceeds the " +
                        "Art. 14(5) TFR threshold — ownership/control verification required before execution.",
                        amountEur, toWallet);
                throw new ComplianceGateException(
                        "Travel Rule: ownership/control of self-hosted wallet " + toWallet
                                + " must be verified before executing this transfer (TFR Art. 14(5))");
            } else {
                log.info("Travel Rule: self-hosted beneficiary wallet={}, originator info recorded.", toWallet);
            }
            return false;
        }

        // MiCA Reg (EU) 2023/1114: from 1 July 2026, transfers to counterparties
        // without CASP authorization must not be executed. Record the rejection
        // before propagating so the audit trail shows the blocked attempt.
        try {
            caspRegistry.assertCounterpartyPermitted(vasp.get().vaspId());
        } catch (ComplianceGateException micaBlock) {
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur, nativeAmount, nativeSymbol,
                    "OUTBOUND", STATUS_BLOCKED_MICA, vasp.get().vaspId(),
                    micaBlock.getMessage(), payload);
            throw micaBlock;
        }

        if (protocols.isEmpty()) {
            // Fail closed: a CASP-to-CASP transfer without the required information
            // would breach TFR Art. 14 — there is no de minimis exemption.
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur, nativeAmount, nativeSymbol,
                    "OUTBOUND", STATUS_FAILED, vasp.get().vaspId(),
                    "No Travel Rule protocol adapter configured", payload);
            throw new ComplianceGateException(
                    "Travel Rule: beneficiary wallet belongs to VASP " + vasp.get().vaspId() +
                    " but no protocol adapter is configured (registerwerk.travel-rule.protocol). " +
                    "TFR Reg (EU) 2023/1113 requires originator/beneficiary information on every " +
                    "CASP-to-CASP transfer — the transfer must not be executed.");
        }

        TravelRuleProtocolPort protocol = protocols.get(0);
        UUID messageId = UUID.randomUUID();
        recordMessage(messageId, assetId, fromWallet, toWallet, amountEur, nativeAmount, nativeSymbol,
                "OUTBOUND", STATUS_PENDING_SEND, vasp.get().vaspId(), null, payload);

        protocol.send(messageId, payload).whenComplete((protocolMessageId, ex) -> {
            if (ex != null) {
                log.error("Travel Rule: failed to send message for transfer fromWallet={}: {}",
                        fromWallet, ex.getMessage());
                completionWriter.markFailed(messageId, ex.getMessage());
            } else {
                log.info("Travel Rule: message sent. protocol={} protocolMsgId={}",
                        protocol.protocolName(), protocolMessageId);
                completionWriter.markSent(messageId, protocol.protocolName(), protocolMessageId,
                        vasp.get().vaspId());
            }
        });
        return true;
    }

    /** Handles an inbound Travel Rule message received from another VASP. */
    @Transactional
    public void receiveInbound(String fromVaspId, Ivms101.TravelRuleMessage payload) {
        String transferReference = validateInbound(fromVaspId, payload);
        log.info("Travel Rule: inbound message from VASP={}", fromVaspId);
        String toWallet = payload.beneficiary().get(0).accountNumber();
        String fromWallet = payload.originator().get(0).accountNumber();

        UUID messageId = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, originator_wallet, beneficiary_wallet,
               ivms101_payload, protocol_message_id, received_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?,?,now(),now())
            ON CONFLICT DO NOTHING
            """,
            messageId, "INBOUND", STATUS_RECEIVED, fromVaspId, fromWallet, toWallet,
            serializePayload(payload), transferReference, Instant.now());

        if (inserted == 0) {
            log.info("Travel Rule: ignored replay from VASP={} transferReference={}",
                    fromVaspId, transferReference);
            return;
        }

        eventPublisher.publishEvent(new TravelRuleMessageReceivedEvent(messageId, null, "SYSTEM", Map.of(
                "originatorVaspId", fromVaspId, "transferReference", transferReference
        )));
    }

    private static String validateInbound(String fromVaspId, Ivms101.TravelRuleMessage payload) {
        if (fromVaspId == null || fromVaspId.isBlank()) {
            throw new IllegalArgumentException("X-Vasp-Id is required");
        }
        if (payload == null || payload.originatingVasp() == null
                || payload.originatingVasp().originatingVasp() == null) {
            throw new IllegalArgumentException("originatingVasp identity is required");
        }
        String payloadVaspId = payload.originatingVasp().originatingVasp().vaspId();
        if (payloadVaspId == null || !fromVaspId.equalsIgnoreCase(payloadVaspId.trim())) {
            throw new IllegalArgumentException("X-Vasp-Id must match originatingVasp.vaspId");
        }
        if (payload.originator() == null || payload.originator().isEmpty()
                || payload.originator().get(0) == null
                || isBlank(payload.originator().get(0).accountNumber())) {
            throw new IllegalArgumentException("At least one originator accountNumber is required");
        }
        if (payload.beneficiary() == null || payload.beneficiary().isEmpty()
                || payload.beneficiary().get(0) == null
                || isBlank(payload.beneficiary().get(0).accountNumber())) {
            throw new IllegalArgumentException("At least one beneficiary accountNumber is required");
        }
        if (payload.transferDetails() == null
                || isBlank(payload.transferDetails().transactionIdentifier())) {
            throw new IllegalArgumentException("transferDetails.transactionIdentifier is required");
        }
        return payload.transferDetails().transactionIdentifier().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Optional<TravelRuleProtocolPort.VaspInfo> resolveVasp(String walletAddress) {
        return protocols.stream()
                .map(p -> p.lookupVasp(walletAddress))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private void recordMessage(UUID messageId, UUID assetId, String fromWallet, String toWallet,
                               BigDecimal amountEur, BigDecimal nativeAmount, String nativeSymbol,
                               String direction, String status,
                               String beneficiaryVaspId, String errorMessage,
                               Ivms101.TravelRuleMessage payload) {
        // Prefer a real EUR valuation when one is ever available; otherwise fall back to the
        // transfer's own native denomination rather than leave both
        // columns silently null — e.g. a decrypted confidential-transfer amount, which this
        // codebase has no FX/price-oracle infrastructure to convert to EUR.
        BigDecimal amount = amountEur != null ? amountEur : nativeAmount;
        String currencySymbol = amountEur != null ? "EUR" : nativeSymbol;
        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, asset_id, beneficiary_vasp_did, originator_wallet,
               beneficiary_wallet, amount, currency_symbol, error_message, ivms101_payload,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,now(),now())
            """,
            messageId, direction, status, assetId, beneficiaryVaspId, fromWallet, toWallet,
            amount, currencySymbol, errorMessage, serializePayload(payload));
    }

    private String serializePayload(Ivms101.TravelRuleMessage payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("IVMS-101 payload could not be serialized", e);
        }
    }

    private static double countRecentFailures(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM travel_rule_message
            WHERE status = ? AND updated_at > now() - interval '24 hours'
            """, Long.class, STATUS_FAILED);
        return count == null ? 0.0 : count;
    }
}
