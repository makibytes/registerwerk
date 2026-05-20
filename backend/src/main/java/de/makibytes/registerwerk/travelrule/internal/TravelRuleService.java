package de.makibytes.registerwerk.travelrule.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates Travel Rule (TFR Reg EU 2023/1113) checks for outbound transfers.
 * Threshold: DE/EU/LI/LU/FR = €1,000.
 *
 * Trigger points (called by blockchain admin controller before forceTransfer/mint):
 * 1. Look up beneficiary wallet in VASP directory → if VASP found, send IVMS-101
 * 2. Store outbound message + await ACK (async)
 * 3. For inbound: handle messages received at /api/v1/public/travel-rule/inbox
 */
@Service
public class TravelRuleService {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleService.class);

    @Value("${registerwerk.travel-rule.threshold-eur:1000}")
    private double thresholdEur;

    private final List<TravelRuleProtocolPort> protocols;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    TravelRuleService(List<TravelRuleProtocolPort> protocols, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.protocols = protocols;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks if a transfer requires Travel Rule message dispatch and sends it.
     * Returns true if a message was sent, false if below threshold or no VASP found.
     */
    @Transactional
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet,
                                 double amountEur, Ivms101.TravelRuleMessage payload) {
        if (amountEur < thresholdEur) {
            log.debug("Travel Rule: transfer below threshold ({} EUR), no message needed.", amountEur);
            return false;
        }

        Optional<TravelRuleProtocolPort.VaspInfo> vasp = resolveVasp(toWallet);
        if (vasp.isEmpty()) {
            log.info("Travel Rule: no VASP found for wallet={}, treating as unhosted.", toWallet);
            recordMessage(assetId, fromWallet, toWallet, amountEur, "OUTBOUND", "SENT", null, payload);
            return false;
        }

        if (protocols.isEmpty()) {
            log.warn("Travel Rule: threshold exceeded but no protocol adapter configured. " +
                     "Configure registerwerk.travel-rule.protocol (TRP/NOTABENE/SYGNA).");
            return false;
        }

        TravelRuleProtocolPort protocol = protocols.get(0);
        UUID messageId = UUID.randomUUID();
        recordMessage(assetId, fromWallet, toWallet, amountEur, "OUTBOUND", "PENDING_SEND", vasp.get().vaspId(), payload);

        protocol.send(messageId, payload).whenComplete((msgId, ex) -> {
            if (ex != null) {
                log.error("Travel Rule: failed to send message for transfer fromWallet={}: {}", fromWallet, ex.getMessage());
                updateStatus(messageId, "FAILED", ex.getMessage());
            } else {
                log.info("Travel Rule: message sent. protocol={} msgId={}", protocol.protocolName(), msgId);
                updateStatus(messageId, "SENT", null);
            }
        });
        return true;
    }

    /** Handles an inbound Travel Rule message received from another VASP. */
    @Transactional
    public void receiveInbound(String fromVaspId, Ivms101.TravelRuleMessage payload) {
        log.info("Travel Rule: inbound message from VASP={}", fromVaspId);
        UUID messageId = UUID.randomUUID();
        String toWallet = payload.beneficiary() != null && !payload.beneficiary().isEmpty()
                ? payload.beneficiary().get(0).accountNumber() : null;
        String fromWallet = payload.originator() != null && !payload.originator().isEmpty()
                ? payload.originator().get(0).accountNumber() : null;

        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, originator_wallet, beneficiary_wallet,
               ivms101_payload, received_at, created_at, updated_at)
            VALUES (?,?::travel_rule_direction,?::travel_rule_status,?,?,?,?::jsonb,?,now(),now())
            """,
            messageId, "INBOUND", "RECEIVED", fromVaspId, fromWallet, toWallet,
            serializePayload(payload), Instant.now());
    }

    private Optional<TravelRuleProtocolPort.VaspInfo> resolveVasp(String walletAddress) {
        return protocols.stream()
                .map(p -> p.lookupVasp(walletAddress))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private void recordMessage(UUID assetId, String fromWallet, String toWallet, double amount,
                                String direction, String status, String vaspId, Ivms101.TravelRuleMessage payload) {
        jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, originator_wallet, beneficiary_wallet,
               amount, ivms101_payload, created_at, updated_at)
            VALUES (?,?::travel_rule_direction,?::travel_rule_status,?,?,?,?,?::jsonb,now(),now())
            """,
            UUID.randomUUID(), direction, status, vaspId, fromWallet, toWallet,
            amount, serializePayload(payload));
    }

    private void updateStatus(UUID messageId, String status, String errorMessage) {
        jdbc.update("UPDATE travel_rule_message SET status=?::travel_rule_status, error_message=?, updated_at=now() WHERE id=?",
                status, errorMessage, messageId);
    }

    private String serializePayload(Ivms101.TravelRuleMessage payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
