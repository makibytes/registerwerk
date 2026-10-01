package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import de.makibytes.registerwerk.travelrule.events.TravelRuleMessageReceivedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates Travel Rule checks under Regulation (EU) 2023/1113 ("TFR") for
 * crypto-asset transfers, applicable since 30 December 2024.
 *
 * <p><strong>No de minimis threshold:</strong> per TFR Art. 14-16 and the EBA Travel
 * Rule Guidelines (EBA/GL/2024/11), originator and beneficiary information must
 * accompany <em>every</em> CASP-to-CASP crypto-asset transfer regardless of amount -
 * unlike fiat wire transfers. The EUR 1,000 threshold exists only for transfers
 * to/from self-hosted addresses: above it, Art. 14(5) requires the originating CASP
 * to verify that the self-hosted address is owned or controlled by its customer.
 *
 * <p><strong>Await-then-submit (6-26):</strong> for a CASP beneficiary the message row is committed as
 * PENDING_SEND ({@code REQUIRES_NEW}), the protocol call is awaited with a bounded timeout and a bounded
 * number of attempts, and only a confirmed SENT lets the caller go on to submit the on-chain operation.
 * A failure persists FAILED and refuses the operation (it can simply be repeated); there is no override
 * until the legal position is settled (parked T6-06). Because every state change is written by the
 * thread that awaited the result, after the insert committed, a result can never be recorded before the
 * row is visible. Crash leftovers are swept by {@link TravelRuleSweeper}.
 *
 * <p><strong>Fail-closed:</strong> if the beneficiary is a VASP but no Travel Rule
 * protocol adapter is configured, the transfer is rejected.
 */
@Service
public class TravelRuleService {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleService.class);

    static final String STATUS_PENDING_SEND = "PENDING_SEND";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_RECEIVED = "RECEIVED";
    /** Self-hosted beneficiary, amount <= EUR 1,000 - info recorded, no counterpart CASP. */
    static final String STATUS_UNHOSTED_RECORDED = "UNHOSTED_RECORDED";
    /** Self-hosted beneficiary, amount > EUR 1,000 (or unknown) - Art. 14(5) ownership verification due. */
    static final String STATUS_UNHOSTED_VERIFY_REQUIRED = "UNHOSTED_VERIFY_REQUIRED";
    /** Self-hosted beneficiary whose control was proven (wallet_control_proof) - Art. 14(5) satisfied. */
    static final String STATUS_UNHOSTED_VERIFIED = "UNHOSTED_VERIFIED";
    /** Beneficiary wallet is an onboarded holder of this register and the operator enabled the exemption. */
    static final String STATUS_INTERNAL_REGISTER_TRANSFER = "INTERNAL_REGISTER_TRANSFER";
    /** Counterparty CASP lacks MiCA authorization - transfer rejected (Reg (EU) 2023/1114). */
    static final String STATUS_BLOCKED_MICA = "BLOCKED_MICA";
    /** Mandatory IVMS-101 originator/beneficiary/transfer data missing - nothing was sent. */
    static final String STATUS_INCOMPLETE_IVMS = "INCOMPLETE_IVMS";
    /** Inbound message lacks mandatory originator/beneficiary data (TFR Art. 16(1)). */
    static final String STATUS_INCOMPLETE = "INCOMPLETE";
    /** Inbound messages under one (peer, reference) with different content. */
    static final String STATUS_CONFLICT = "CONFLICT";
    /** Inbound message from a blocked/revoked CASP. */
    static final String STATUS_REJECTED_CASP = "REJECTED_CASP";

    /** Art. 14(5) TFR self-hosted address verification threshold (not a messaging threshold). */
    @Value("${registerwerk.travel-rule.self-hosted-verification-threshold-eur:1000}")
    private BigDecimal selfHostedVerificationThresholdEur;

    private final List<TravelRuleProtocolPort> protocols;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CaspRegistryService caspRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TravelRuleCompletionWriter completionWriter;
    private final WalletControlProofService proofService;
    private final TravelRuleProperties properties;
    private final Clock clock;

    TravelRuleService(List<TravelRuleProtocolPort> protocols, JdbcTemplate jdbc,
                      ObjectMapper objectMapper, CaspRegistryService caspRegistry,
                      ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                      TravelRuleCompletionWriter completionWriter, WalletControlProofService proofService,
                      TravelRuleProperties properties, Clock clock) {
        this.protocols = protocols;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.caspRegistry = caspRegistry;
        this.eventPublisher = eventPublisher;
        this.completionWriter = completionWriter;
        this.proofService = proofService;
        this.properties = properties;
        this.clock = clock;

        Gauge.builder("registerwerk_travelrule_failed_messages_recent_total", jdbc, TravelRuleService::countRecentFailures)
                .description("Count of travel_rule_message rows with status=FAILED in the last 24h")
                .register(meterRegistry);
        Gauge.builder("registerwerk_travelrule_open_items", jdbc, TravelRuleService::countOpenItems)
                .description("Travel Rule messages needing operator attention (FAILED, stale PENDING_SEND, INCOMPLETE, CONFLICT)")
                .register(meterRegistry);
    }

    /** Convenience overload: EUR amount only. */
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet,
                                BigDecimal amountEur, Ivms101.TravelRuleMessage payload) {
        return checkAndSend(assetId, fromWallet, toWallet, amountEur, null, null, payload);
    }

    /** Convenience overload: optional native amount fallback. */
    public boolean checkAndSend(UUID assetId, String fromWallet, String toWallet, BigDecimal amountEur,
                                BigDecimal nativeAmount, String nativeSymbol, Ivms101.TravelRuleMessage payload) {
        return checkAndSend(new TravelRuleGate.TransferContext(assetId, fromWallet, toWallet, amountEur, null, null,
                nativeAmount, nativeSymbol, null), payload);
    }

    /**
     * Performs the Travel Rule check for an outbound transfer. For a CASP beneficiary the message is
     * delivered (awaited) before this method returns; it is deliberately <em>not</em> transactional so
     * that every row it writes commits independently of the caller's transaction.
     *
     * @return true if a message was delivered to a beneficiary VASP; false if the beneficiary is
     *         self-hosted (recorded locally instead)
     * @throws ComplianceGateException if the transfer must not proceed (blocked CASP, incomplete data,
     *         delivery failure, unverified self-hosted wallet)
     */
    public boolean checkAndSend(TravelRuleGate.TransferContext ctx, Ivms101.TravelRuleMessage payload) {
        Optional<TravelRuleProtocolPort.VaspInfo> resolved = resolveVasp(ctx.toWallet());
        if (resolved.isEmpty()) {
            handleSelfHosted(ctx, payload);
            return false;
        }
        TravelRuleProtocolPort.VaspInfo vasp = resolved.get();

        // MiCA Reg (EU) 2023/1114: from 1 July 2026, transfers to counterparties without CASP
        // authorization must not be executed. Record the rejection (own transaction) before propagating.
        try {
            caspRegistry.assertCounterpartyPermitted(vasp);
        } catch (ComplianceGateException micaBlock) {
            record(UUID.randomUUID(), ctx, STATUS_BLOCKED_MICA, vasp.vaspId(), micaBlock.getMessage(), payload, null);
            throw micaBlock;
        }

        if (protocols.isEmpty()) {
            record(UUID.randomUUID(), ctx, STATUS_FAILED, vasp.vaspId(),
                    "No Travel Rule protocol adapter configured", payload, null);
            throw new ComplianceGateException(
                    "Travel Rule: beneficiary wallet belongs to VASP " + vasp.vaspId() +
                    " but no protocol adapter is configured (registerwerk.travel-rule.protocol). " +
                    "TFR Reg (EU) 2023/1113 requires originator/beneficiary information on every " +
                    "CASP-to-CASP transfer - the transfer must not be executed.");
        }

        UUID messageId = UUID.randomUUID();
        Ivms101.TravelRuleMessage message;
        try {
            message = complete(messageId, ctx, vasp, payload);
        } catch (ComplianceGateException incomplete) {
            record(messageId, ctx, STATUS_INCOMPLETE_IVMS, vasp.vaspId(), incomplete.getMessage(), payload, null);
            throw incomplete;
        }

        TravelRuleProtocolPort protocol = protocols.get(0);
        record(messageId, ctx, STATUS_PENDING_SEND, vasp.vaspId(), null, message, null);
        deliver(messageId, protocol, message, vasp, ctx.fromWallet());
        return true;
    }

    private void handleSelfHosted(TravelRuleGate.TransferContext ctx, Ivms101.TravelRuleMessage payload) {
        // Art. 14(5) satisfied by a verified wallet-control proof of the registered holder.
        Optional<UUID> proof = proofService.findValidProof(ctx.assetId(), ctx.toWallet());
        if (proof.isPresent()) {
            record(UUID.randomUUID(), ctx, STATUS_UNHOSTED_VERIFIED, null, null, payload, proof.get());
            log.info("Travel Rule: self-hosted wallet {} verified by proof {}", ctx.toWallet(), proof.get());
            return;
        }
        if (properties.isRegisterInternalExempt()
                && proofService.isRegisteredHolderWallet(ctx.assetId(), ctx.toWallet())) {
            record(UUID.randomUUID(), ctx, STATUS_INTERNAL_REGISTER_TRANSFER, null, null, payload, null);
            return;
        }
        // Unknown EUR value is treated conservatively as above the Art. 14(5) threshold - fail closed.
        boolean verificationRequired = ctx.amountEur() == null
                || ctx.amountEur().compareTo(selfHostedVerificationThresholdEur) > 0;
        String status = verificationRequired ? STATUS_UNHOSTED_VERIFY_REQUIRED : STATUS_UNHOSTED_RECORDED;
        record(UUID.randomUUID(), ctx, status, null, null, payload, null);
        if (verificationRequired) {
            log.warn("Travel Rule: transfer of {} EUR to self-hosted wallet {} exceeds the " +
                    "Art. 14(5) TFR threshold - ownership/control verification required before execution.",
                    ctx.amountEur(), ctx.toWallet());
            throw new ComplianceGateException(
                    "Travel Rule: ownership/control of self-hosted wallet " + ctx.toWallet()
                            + " must be verified before executing this transfer (TFR Art. 14(5)). Record a "
                            + "wallet-control proof (signed message or attested evidence) for the holder via "
                            + "/api/v1/compliance/travel-rule/wallet-proofs, then repeat the operation.");
        }
        log.info("Travel Rule: self-hosted beneficiary wallet={}, originator info recorded.", ctx.toWallet());
    }

    /** Attaches own VASP, resolved beneficiary VASP and transfer details; refuses incomplete data. */
    private Ivms101.TravelRuleMessage complete(UUID messageId, TravelRuleGate.TransferContext ctx,
                                               TravelRuleProtocolPort.VaspInfo vasp,
                                               Ivms101.TravelRuleMessage payload) {
        TravelRuleProperties.OwnVasp own = properties.getOwnVasp();
        if (own.identifier() == null) {
            throw new ComplianceGateException("Travel Rule: the operator's own VASP identity is not configured "
                    + "(registerwerk.travel-rule.own-vasp.did / lei / legal-name) - cannot build originatingVasp");
        }
        BigDecimal amount = ctx.nativeAmount() != null ? ctx.nativeAmount() : ctx.amountEur();
        String symbol = ctx.nativeAmount() != null ? ctx.nativeSymbol() : "EUR";
        if (amount == null || symbol == null || symbol.isBlank()) {
            throw new ComplianceGateException("Travel Rule: the transfer amount/asset is unknown - the "
                    + "IVMS-101 transferDetails cannot be completed");
        }
        Ivms101.TransferDetails details = new Ivms101.TransferDetails(
                messageId.toString(), LocalDate.now(clock).toString(), amount.stripTrailingZeros().toPlainString(),
                symbol, "ON_CHAIN", ctx.assetReference());
        Ivms101.TravelRuleMessage message = new Ivms101.TravelRuleMessage(
                new Ivms101.OriginatingVasp(new Ivms101.VaspIdentity(own.identifier(), own.getLegalName())),
                payload == null ? null : payload.originator(),
                new Ivms101.BeneficiaryVasp(new Ivms101.VaspIdentity(
                        vasp.vaspId() != null && !vasp.vaspId().isBlank() ? vasp.vaspId() : vasp.lei(),
                        vasp.legalName())),
                payload == null ? null : payload.beneficiary(),
                details);
        List<String> missing = Ivms101Completeness.missingForOutbound(message);
        if (!missing.isEmpty()) {
            throw new ComplianceGateException("Travel Rule: mandatory IVMS-101 data missing (" + String.join(", ", missing)
                    + ") - TFR Art. 14(1)/(2) information must accompany the transfer; nothing was sent");
        }
        return message;
    }

    /** Awaits delivery with a bounded timeout and bounded attempts; persists the outcome; throws on failure. */
    private void deliver(UUID messageId, TravelRuleProtocolPort protocol, Ivms101.TravelRuleMessage message,
                         TravelRuleProtocolPort.VaspInfo vasp, String fromWallet) {
        int attempts = Math.max(1, properties.getSendAttempts());
        long timeout = Math.max(1, properties.getSendTimeoutSeconds());
        String lastError = "unknown";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String protocolMessageId = protocol.send(messageId, message, vasp).get(timeout, TimeUnit.SECONDS);
                log.info("Travel Rule: message sent. protocol={} protocolMsgId={}", protocol.protocolName(), protocolMessageId);
                completionWriter.markSent(messageId, protocol.protocolName(), protocolMessageId, vasp.vaspId());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "interrupted";
                break;
            } catch (TimeoutException e) {
                lastError = "no acceptance within " + timeout + "s";
            } catch (ExecutionException e) {
                lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            } catch (RuntimeException e) {
                lastError = e.getMessage();
            }
            log.error("Travel Rule: send attempt {}/{} failed for transfer fromWallet={}: {}",
                    attempt, attempts, fromWallet, lastError);
            if (attempt < attempts) {
                try {
                    Thread.sleep(properties.getSendBackoffMillis() * (1L << (attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        completionWriter.markFailed(messageId, lastError);
        throw new ComplianceGateException("Travel Rule: the message to VASP " + vasp.vaspId()
                + " could not be delivered (" + lastError + "); the operation was not executed and can be repeated. "
                + "Open items: GET /api/v1/compliance/travel-rule/open");
    }

    /**
     * Re-delivers a queued FAILED message (crash leftover). The beneficiary VASP is re-resolved from the
     * stored beneficiary wallet. Returns true when the message is now SENT.
     */
    public boolean redeliver(UUID messageId, String payloadJson, String beneficiaryWallet) {
        if (protocols.isEmpty()) {
            return false;
        }
        TravelRuleProtocolPort protocol = protocols.get(0);
        try {
            Ivms101.TravelRuleMessage message = objectMapper.readValue(payloadJson, Ivms101.TravelRuleMessage.class);
            TravelRuleProtocolPort.VaspInfo vasp = resolveVasp(beneficiaryWallet).orElseThrow(() ->
                    new IllegalStateException("beneficiary VASP no longer resolvable"));
            caspRegistry.assertCounterpartyPermitted(vasp);
            String id = protocol.send(messageId, message, vasp)
                    .get(Math.max(1, properties.getSendTimeoutSeconds()), TimeUnit.SECONDS);
            completionWriter.markSent(messageId, protocol.protocolName(), id, vasp.vaspId());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            String cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            int attempts = jdbc.queryForObject("SELECT attempts FROM travel_rule_message WHERE id=?", Integer.class, messageId) + 1;
            Instant next = attempts >= properties.getMaxRetryAttempts() ? null
                    : Instant.now(clock).plus(Duration.ofMinutes(1L << Math.min(attempts, 8)));
            completionWriter.markFailed(messageId, cause, next);
            if (next == null) {
                completionWriter.alertRetryExhausted(messageId, attempts);
            }
            return false;
        }
    }

    // ── Inbound ────────────────────────────────────────────────────────────────────────────────

    /**
     * Handles an inbound Travel Rule message from an <em>authenticated</em> peer (the caller has already
     * proven {@code authenticatedPeerId}; this method additionally requires the payload to claim the same
     * identity). Dedup is keyed on (peer, reference, payload hash): a re-delivery is idempotent, a
     * different payload under the same reference is stored and both rows are flagged CONFLICT - a peer
     * can never suppress another message by pre-claiming a reference.
     */
    @Transactional
    public void receiveInbound(String authenticatedPeerId, Ivms101.TravelRuleMessage payload) {
        String transferReference = validateInbound(authenticatedPeerId, payload);
        String toWallet = payload.beneficiary().get(0).accountNumber();
        String fromWallet = payload.originator().get(0).accountNumber();
        String payloadJson = serializePayload(payload);
        String hash = sha256Hex(payloadJson);
        var originating = payload.originatingVasp().originatingVasp();

        try {
            caspRegistry.assertInboundSenderPermitted(new TravelRuleProtocolPort.VaspInfo(
                    authenticatedPeerId, originating.legalName(), null, null, null));
        } catch (ComplianceGateException blocked) {
            completionWriter.insertInboundRejection(UUID.randomUUID(), authenticatedPeerId, fromWallet, toWallet,
                    transferReference, hash, payloadJson, STATUS_REJECTED_CASP, blocked.getMessage());
            eventPublisher.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.INBOUND_REJECTED,
                    "TravelRuleMessage", null, null, "SYSTEM",
                    Map.of("peer", authenticatedPeerId, "reason", blocked.getMessage())));
            throw new AccessDeniedException("Sender VASP is not permitted: " + blocked.getMessage());
        }

        List<String> missing = Ivms101Completeness.missingForInbound(payload);
        String status = missing.isEmpty() ? STATUS_RECEIVED : STATUS_INCOMPLETE;
        String error = missing.isEmpty() ? null : "Missing mandatory IVMS-101 data: " + String.join(", ", missing);
        String detailsJson = payload.transferDetails() == null ? null : serializeAny(payload.transferDetails());
        BigDecimal amount = parseAmount(payload.transferDetails() == null ? null : payload.transferDetails().instructedAmount());
        String symbol = payload.transferDetails() == null ? null : payload.transferDetails().currencyOfTransfer();

        UUID messageId = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO travel_rule_message
              (id, direction, status, originator_vasp_did, peer_vasp_id, originator_wallet, beneficiary_wallet,
               ivms101_payload, transfer_details, amount, currency_symbol, error_message, payload_hash,
               protocol_message_id, received_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,now(),now())
            ON CONFLICT DO NOTHING
            """,
            messageId, "INBOUND", status, authenticatedPeerId, authenticatedPeerId, fromWallet, toWallet,
            payloadJson, detailsJson, amount, symbol, error, hash, transferReference, java.sql.Timestamp.from(Instant.now()));

        if (inserted == 0) {
            log.info("Travel Rule: idempotent re-delivery from VASP={} transferReference={}",
                    authenticatedPeerId, transferReference);
            return;
        }

        int others = jdbc.update("""
            UPDATE travel_rule_message SET status=?, updated_at=now()
             WHERE direction='INBOUND' AND originator_vasp_did=? AND protocol_message_id=? AND payload_hash<>?
               AND status <> ?
            """, STATUS_CONFLICT, authenticatedPeerId, transferReference, hash, STATUS_CONFLICT);
        if (others > 0) {
            jdbc.update("UPDATE travel_rule_message SET status=?, error_message=?, updated_at=now() WHERE id=?",
                    STATUS_CONFLICT, "Different payloads received under the same reference", messageId);
            eventPublisher.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.INBOUND_CONFLICT,
                    "TravelRuleMessage", messageId, null, "SYSTEM",
                    Map.of("peer", authenticatedPeerId, "transferReference", transferReference)));
        } else if (!missing.isEmpty()) {
            eventPublisher.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.INBOUND_INCOMPLETE,
                    "TravelRuleMessage", messageId, null, "SYSTEM",
                    Map.of("peer", authenticatedPeerId, "missing", missing)));
        }

        tryMatch(messageId, transferReference, fromWallet, toWallet, amount);
        eventPublisher.publishEvent(new TravelRuleMessageReceivedEvent(messageId, null, "SYSTEM", Map.of(
                "originatorVaspId", authenticatedPeerId, "transferReference", transferReference, "status",
                others > 0 ? STATUS_CONFLICT : status
        )));
    }

    /** Links an inbound row to the on-chain transfer it describes; true when matched. */
    boolean tryMatch(UUID messageId, String transferReference, String fromWallet, String toWallet, BigDecimal amount) {
        List<UUID> byHash = jdbc.queryForList("""
            SELECT id FROM token_transfer WHERE LOWER(tx_hash) = LOWER(?) ORDER BY occurred_at DESC LIMIT 2
            """, UUID.class, transferReference);
        List<UUID> candidates = byHash.size() == 1 ? byHash : jdbc.queryForList("""
            SELECT id FROM token_transfer
             WHERE LOWER(from_address) = LOWER(?) AND LOWER(to_address) = LOWER(?)
               AND (?::numeric IS NULL OR amount = ?::numeric)
               AND occurred_at > now() - interval '30 days'
             ORDER BY occurred_at DESC LIMIT 2
            """, UUID.class, fromWallet, toWallet, amount, amount);
        if (candidates.size() != 1) {
            return false;
        }
        jdbc.update("UPDATE travel_rule_message SET matched_transfer_id=?, matched_at=now(), updated_at=now() WHERE id=?",
                candidates.get(0), messageId);
        return true;
    }

    /** Re-tries matching for inbound messages that arrived before the indexer saw the transfer. */
    public int rematchInbound() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, protocol_message_id, originator_wallet, beneficiary_wallet, amount FROM travel_rule_message
             WHERE direction='INBOUND' AND matched_transfer_id IS NULL AND status IN ('RECEIVED','INCOMPLETE')
               AND created_at > now() - interval '30 days'
             ORDER BY created_at LIMIT 100
            """);
        int matched = 0;
        for (Map<String, Object> r : rows) {
            if (tryMatch((UUID) r.get("id"), (String) r.get("protocol_message_id"),
                    (String) r.get("originator_wallet"), (String) r.get("beneficiary_wallet"),
                    (BigDecimal) r.get("amount"))) {
                matched++;
            }
        }
        return matched;
    }

    /** Open items: everything an operator must look at. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openItems() {
        return jdbc.queryForList("""
            SELECT id, direction, status, beneficiary_vasp_did, peer_vasp_id, originator_wallet, beneficiary_wallet,
                   amount, currency_symbol, error_message, attempts, next_retry_at, created_at, updated_at
              FROM travel_rule_message
             WHERE status IN ('PENDING_SEND','FAILED','INCOMPLETE','CONFLICT','REJECTED_CASP')
             ORDER BY updated_at DESC LIMIT 500
            """);
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

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Optional<TravelRuleProtocolPort.VaspInfo> resolveVasp(String walletAddress) {
        return protocols.stream()
                .map(p -> p.lookupVasp(walletAddress))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /** Writes an outbound row in its own transaction (survives rollback of the caller's operation). */
    private void record(UUID messageId, TravelRuleGate.TransferContext ctx, String status,
                        String beneficiaryVaspId, String errorMessage, Ivms101.TravelRuleMessage payload, UUID proofId) {
        // Prefer a real EUR valuation when one is available; otherwise fall back to the
        // transfer's own native denomination rather than leave both columns silently null.
        BigDecimal amount = ctx.amountEur() != null ? ctx.amountEur() : ctx.nativeAmount();
        String currencySymbol = ctx.amountEur() != null ? "EUR" : ctx.nativeSymbol();
        String details = payload != null && payload.transferDetails() != null ? serializeAny(payload.transferDetails()) : null;
        completionWriter.insertOutbound(new TravelRuleCompletionWriter.NewMessage(messageId, ctx.assetId(), status,
                beneficiaryVaspId, ctx.fromWallet(), ctx.toWallet(), amount, currencySymbol, errorMessage,
                serializePayload(payload), details, ctx.valuationSource(), ctx.valuationAt(), proofId));
    }

    private String serializePayload(Ivms101.TravelRuleMessage payload) {
        return serializeAny(payload);
    }

    private String serializeAny(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("IVMS-101 payload could not be serialized", e);
        }
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double countRecentFailures(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM travel_rule_message
            WHERE status = ? AND updated_at > now() - interval '24 hours'
            """, Long.class, STATUS_FAILED);
        return count == null ? 0.0 : count;
    }

    private static double countOpenItems(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM travel_rule_message
            WHERE status IN ('FAILED','PENDING_SEND','INCOMPLETE','CONFLICT')
            """, Long.class);
        return count == null ? 0.0 : count;
    }
}
