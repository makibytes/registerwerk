package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    TravelRuleService(List<TravelRuleProtocolPort> protocols, JdbcTemplate jdbc,
                      ObjectMapper objectMapper, CaspRegistryService caspRegistry) {
        this.protocols = protocols;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.caspRegistry = caspRegistry;
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
    @Transactional
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet,
                                BigDecimal amountEur, Ivms101.TravelRuleMessage payload) {
        Optional<TravelRuleProtocolPort.VaspInfo> vasp = resolveVasp(toWallet);
        if (vasp.isEmpty()) {
            // Self-hosted (unhosted) beneficiary: no counterpart CASP to message.
            // Unknown EUR value is treated conservatively as above the Art. 14(5)
            // threshold (verification required) — fail closed.
            boolean verificationRequired = amountEur == null
                    || amountEur.compareTo(selfHostedVerificationThresholdEur) > 0;
            String status = verificationRequired ? STATUS_UNHOSTED_VERIFY_REQUIRED : STATUS_UNHOSTED_RECORDED;
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur,
                    "OUTBOUND", status, null, null, payload);
            if (verificationRequired) {
                log.warn("Travel Rule: transfer of {} EUR to self-hosted wallet {} exceeds the " +
                        "Art. 14(5) TFR threshold — ownership/control verification required before execution.",
                        amountEur, toWallet);
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
        } catch (IllegalStateException micaBlock) {
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur,
                    "OUTBOUND", STATUS_BLOCKED_MICA, vasp.get().vaspId(),
                    micaBlock.getMessage(), payload);
            throw micaBlock;
        }

        if (protocols.isEmpty()) {
            // Fail closed: a CASP-to-CASP transfer without the required information
            // would breach TFR Art. 14 — there is no de minimis exemption.
            recordMessage(UUID.randomUUID(), assetId, fromWallet, toWallet, amountEur,
                    "OUTBOUND", STATUS_FAILED, vasp.get().vaspId(),
                    "No Travel Rule protocol adapter configured", payload);
            throw new IllegalStateException(
                    "Travel Rule: beneficiary wallet belongs to VASP " + vasp.get().vaspId() +
                    " but no protocol adapter is configured (registerwerk.travel-rule.protocol). " +
                    "TFR Reg (EU) 2023/1113 requires originator/beneficiary information on every " +
                    "CASP-to-CASP transfer — the transfer must not be executed.");
        }

        TravelRuleProtocolPort protocol = protocols.get(0);
        UUID messageId = UUID.randomUUID();
        recordMessage(messageId, assetId, fromWallet, toWallet, amountEur,
                "OUTBOUND", STATUS_PENDING_SEND, vasp.get().vaspId(), null, payload);

        protocol.send(messageId, payload).whenComplete((protocolMessageId, ex) -> {
            if (ex != null) {
                log.error("Travel Rule: failed to send message for transfer fromWallet={}: {}",
                        fromWallet, ex.getMessage());
                updateStatus(messageId, STATUS_FAILED, ex.getMessage());
            } else {
                log.info("Travel Rule: message sent. protocol={} protocolMsgId={}",
                        protocol.protocolName(), protocolMessageId);
                markSent(messageId, protocol.protocolName(), protocolMessageId);
            }
        });
        return true;
    }

    /** Handles an inbound Travel Rule message received from another VASP. */
    @Transactional
    public void receiveInbound(String fromVaspId, Ivms101.TravelRuleMessage payload) {
        log.info("Travel Rule: inbound message from VASP={}", fromVaspId);
        String toWallet = payload.beneficiary() != null && !payload.beneficiary().isEmpty()
                ? payload.beneficiary().get(0).accountNumber() : null;
        String fromWallet = payload.originator() != null && !payload.originator().isEmpty()
                ? payload.originator().get(0).accountNumber() : null;

        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, originator_wallet, beneficiary_wallet,
               ivms101_payload, received_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?,now(),now())
            """,
            UUID.randomUUID(), "INBOUND", STATUS_RECEIVED, fromVaspId, fromWallet, toWallet,
            serializePayload(payload), Instant.now());
    }

    private Optional<TravelRuleProtocolPort.VaspInfo> resolveVasp(String walletAddress) {
        return protocols.stream()
                .map(p -> p.lookupVasp(walletAddress))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private void recordMessage(UUID messageId, UUID assetId, String fromWallet, String toWallet,
                               BigDecimal amount, String direction, String status,
                               String beneficiaryVaspId, String errorMessage,
                               Ivms101.TravelRuleMessage payload) {
        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, asset_id, beneficiary_vasp_did, originator_wallet,
               beneficiary_wallet, amount, currency_symbol, error_message, ivms101_payload,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,now(),now())
            """,
            messageId, direction, status, assetId, beneficiaryVaspId, fromWallet, toWallet,
            amount, "EUR", errorMessage, serializePayload(payload));
    }

    private void updateStatus(UUID messageId, String status, String errorMessage) {
        jdbc.update("UPDATE travel_rule_message SET status=?, error_message=?, updated_at=now() WHERE id=?",
                status, errorMessage, messageId);
    }

    private void markSent(UUID messageId, String protocolName, String protocolMessageId) {
        jdbc.update("""
            UPDATE travel_rule_message
            SET status=?, protocol=?, protocol_message_id=?, sent_at=now(), updated_at=now()
            WHERE id=?
            """,
            STATUS_SENT, protocolName, protocolMessageId, messageId);
    }

    private String serializePayload(Ivms101.TravelRuleMessage payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
