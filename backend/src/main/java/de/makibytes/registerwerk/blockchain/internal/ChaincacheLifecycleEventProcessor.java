package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.blockchain.api.LifecycleLogProjectionPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.indexer.api.TypedReorgCompensationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Registerwerk's transactional inbox boundary for the Chaincache v2 lifecycle stream.
 * A successful return means the inbox row, occurrence transition, projection and local cursor
 * have committed together; only then may the transport ACK the source sequence.
 */
@Service
class ChaincacheLifecycleEventProcessor {

    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BlockFinalityFeed finalityFeed;
    private final ChaincacheReorgCoordinator reorgCoordinator;
    private final AssetDeploymentRepository deploymentRepository;
    private final LifecycleLogProjectionPort logProjection;

    ChaincacheLifecycleEventProcessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            BlockFinalityFeed finalityFeed, ChaincacheReorgCoordinator reorgCoordinator,
            AssetDeploymentRepository deploymentRepository, LifecycleLogProjectionPort logProjection) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.finalityFeed = finalityFeed;
        this.reorgCoordinator = reorgCoordinator;
        this.deploymentRepository = deploymentRepository;
        this.logProjection = logProjection;
    }

    long earliestDeploymentBlock(UUID chainConfigId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MIN(block_number), 0)
                  FROM asset_deployment
                 WHERE chain_config_id = ? AND block_number IS NOT NULL
                """, Long.class, chainConfigId);
        return value == null ? 0L : Math.max(0L, value);
    }

    @Transactional(noRollbackFor = ChaincacheProtocolException.class)
    public void process(UUID chainConfigId, String consumerId, String expectedChainKey,
            String expectedDurabilityDomainId, JsonNode rawEvent) {
        Envelope event = parse(rawEvent, expectedChainKey, expectedDurabilityDomainId);
        String payloadHash = payloadHash(objectMapper, rawEvent);
        String rawJson = objectMapper.writeValueAsString(rawEvent);

        int inserted = jdbcTemplate.update("""
                INSERT INTO chaincache_event_inbox
                  (durability_domain_id, chain_config_id, chain_key, source_sequence, event_id,
                   schema_version, event_kind, finality, payload_hash, raw_event)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (durability_domain_id, chain_config_id, event_id) DO NOTHING
                """, event.durabilityDomainId(), chainConfigId, event.chainKey(), event.sequence(),
                event.eventId(), event.schemaVersion(), event.kind(), nullableName(event.finality()),
                payloadHash, rawJson);

        InboxRow inbox = inbox(event.durabilityDomainId(), chainConfigId, event.eventId());
        if (!inbox.payloadHash().equals(payloadHash)) {
            quarantineInbox(inbox.id(), "eventId was reused with a different immutable payload");
            quarantineSubscription(chainConfigId, consumerId, event,
                    "eventId was reused with a different immutable payload");
            throw new ChaincacheProtocolException("Conflicting Chaincache eventId " + event.eventId());
        }
        if ("QUARANTINED".equals(inbox.state())) {
            throw new ChaincacheProtocolException("Chaincache event is quarantined: " + event.eventId());
        }

        SubscriptionRow cursor = lockCursor(chainConfigId, consumerId, event);
        if (cursor.lastSequence() != null) {
            long expected = cursor.lastSequence() + 1;
            if (event.sequence() <= cursor.lastSequence()) {
                // A benign replay of already-durably-applied history — a lease takeover mid-batch,
                // a Chaincache cursor restore, or any redelivery window wider than one event all
                // legitimately redeliver a sequence this consumer already committed and acknowledged.
                // The payload-hash check above already proved this is the identical immutable event
                // (a genuine conflicting reuse threw before reaching here), so once its own inbox
                // row confirms it was actually applied, this is a no-op ack: never re-apply, never
                // advance the cursor backwards, and — critically — never treat "behind the cursor"
                // as corruption the way a *forward* gap is. An inbox row that is NOT PROCESSED here
                // (state RECEIVED, meaning the cursor advanced past this sequence without this exact
                // event ever completing apply()) is a genuine anomaly under this method's own
                // transactional semantics and is quarantined exactly like a forward gap.
                if ("PROCESSED".equals(inbox.state())) {
                    recordRedelivery(inbox.id());
                    return;
                }
                String reason = "lifecycle sequence " + event.sequence() + " is behind the acknowledged "
                        + "cursor (" + cursor.lastSequence() + ") but was never recorded as processed";
                quarantineInbox(inbox.id(), reason);
                quarantineSubscription(chainConfigId, consumerId, event, reason);
                throw new ChaincacheProtocolException(reason);
            }
            if (event.sequence() != expected) {
                String reason = "lifecycle sequence gap/regression: expected " + expected
                        + " but received " + event.sequence();
                quarantineInbox(inbox.id(), reason);
                quarantineSubscription(chainConfigId, consumerId, event, reason);
                throw new ChaincacheProtocolException(reason);
            }
        } else if (inserted == 0 && "PROCESSED".equals(inbox.state())) {
            // A local subscription row may have been restored independently of the inbox.  The
            // immutable processed row is still authoritative and can safely repair its cursor.
            advanceCursor(chainConfigId, consumerId, event);
            recordRedelivery(inbox.id());
            return;
        }

        apply(chainConfigId, event);
        jdbcTemplate.update("""
                UPDATE chaincache_event_inbox
                   SET processing_state = 'PROCESSED', processed_at = NOW(), last_received_at = NOW(),
                       delivery_count = CASE WHEN ? = 0 THEN delivery_count + 1 ELSE delivery_count END,
                       last_error = NULL
                 WHERE id = ?
                """, inserted, inbox.id());
        advanceCursor(chainConfigId, consumerId, event);
    }

    private void apply(UUID chainConfigId, Envelope event) {
        switch (event.kind()) {
            case "BLOCK", "FINALITY_CHANGED" -> finalityFeed.recordObservation(chainConfigId,
                    requiredBlockNumber(event), requiredText(event.blockHash(), "blockHash"),
                    requiredFinality(event));
            case "LOG" -> applyLog(chainConfigId, event);
            case "REORG" -> applyReorg(chainConfigId, event);
            case "RETRACTION" -> applyRetraction(chainConfigId, event);
            default -> throw new IllegalArgumentException("Unsupported Chaincache lifecycle kind: " + event.kind());
        }
    }

    private void applyLog(UUID chainConfigId, Envelope event) {
        JsonNode log = requireObject(event.payload(), "LOG payload");
        String contract = normalizeHex(requiredText(log.path("address").asText(null), "payload.address"));
        String txHash = normalizeHex(requiredText(log.path("transactionHash").asText(null),
                "payload.transactionHash"));
        int logIndex = requiredHexInt(log, "logIndex");
        Integer transactionIndex = optionalHexInt(log, "transactionIndex");
        long blockNumber = requiredBlockNumber(event);
        String blockHash = normalizeHex(requiredText(event.blockHash(), "blockHash"));
        FinalityLevel finality = requiredFinality(event);
        String tenure = event.canonicalTenure();
        String logicalEventId = logicalEventId(event.eventId());

        OccurrenceRow occurrence = findOccurrenceForUpdate(chainConfigId, blockHash, txHash,
                logIndex, contract, tenure);
        if (occurrence == null) {
            UUID occurrenceId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO chain_event_occurrence
                      (id, chain_config_id, durability_domain_id, chain_key, block_number, block_hash,
                       transaction_hash, transaction_index, log_index, contract_address,
                       canonical_tenure, logical_event_id, first_event_id, last_event_id,
                       current_finality, canonical, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    """, occurrenceId, chainConfigId, event.durabilityDomainId(), event.chainKey(),
                    blockNumber, blockHash, txHash, transactionIndex, logIndex, contract, tenure,
                    logicalEventId, event.eventId(), event.eventId(), finality.name(),
                    Timestamp.from(event.createdAt()));
            UUID transferId = projectTransfer(chainConfigId, event, log, contract, txHash,
                    logIndex, blockNumber, blockHash, finality);
            if (transferId != null) {
                jdbcTemplate.update("UPDATE chain_event_occurrence SET token_transfer_id = ? WHERE id = ?",
                        transferId, occurrenceId);
            }
            return;
        }

        validatePromotion(occurrence.finality(), finality, event.eventId());
        jdbcTemplate.update("""
                UPDATE chain_event_occurrence
                   SET current_finality = ?, last_event_id = ?, updated_at = NOW()
                 WHERE id = ?
                """, finality.name(), event.eventId(), occurrence.id());
        if (occurrence.tokenTransferId() != null && occurrence.finality() != finality) {
            logProjection.promote(occurrence.tokenTransferId(), finality);
        }
    }

    private UUID projectTransfer(UUID chainConfigId, Envelope event, JsonNode log, String contract,
            String txHash, int logIndex, long blockNumber, String blockHash, FinalityLevel finality) {
        JsonNode topics = log.path("topics");
        if (!topics.isArray() || (topics.size() != 3 && topics.size() != 4)
                || !TRANSFER_TOPIC.equalsIgnoreCase(topics.path(0).asText())) {
            return null;
        }
        AssetDeployment deployment = deploymentRepository
                .findFirstByChainConfigIdAndContractAddressIgnoreCase(chainConfigId, contract)
                .orElse(null);
        if (deployment == null) {
            return null;
        }

        String from = topicAddress(topics.path(1).asText());
        String to = topicAddress(topics.path(2).asText());
        boolean erc721 = topics.size() == 4;
        BigDecimal value = erc721 ? unsignedHex(topics.path(3).asText())
                : unsignedHex(log.path("data").asText());

        ChainConfig chain = jdbcTemplate.queryForObject(
                "SELECT block_explorer_url FROM chain_config WHERE id = ?",
                (rs, rowNum) -> {
                    ChainConfig valueChain = new ChainConfig();
                    valueChain.setBlockExplorerUrl(rs.getString(1));
                    return valueChain;
                }, chainConfigId);
        String explorerUrl = chain != null && chain.getBlockExplorerUrl() != null
                && !chain.getBlockExplorerUrl().isBlank()
                ? chain.getBlockExplorerUrl().replaceAll("/+$", "") + "/tx/" + txHash : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = objectMapper.convertValue(log, Map.class);
        LifecycleLogProjectionPort.EventType eventType = ZERO_ADDRESS.equals(from)
                ? LifecycleLogProjectionPort.EventType.MINT
                : ZERO_ADDRESS.equals(to) ? LifecycleLogProjectionPort.EventType.BURN
                : LifecycleLogProjectionPort.EventType.TRANSFER;
        return logProjection.create(new LifecycleLogProjectionPort.TransferProjection(
                deployment.getAssetId(), deployment.getId(), chainConfigId, contract,
                ZERO_ADDRESS.equals(from) ? null : from, ZERO_ADDRESS.equals(to) ? null : to,
                erc721 ? value : null, erc721 ? BigDecimal.ONE : value, eventType, txHash,
                blockNumber, logIndex, event.createdAt(), explorerUrl, raw, finality, blockHash));
    }

    private void applyRetraction(UUID chainConfigId, Envelope event) {
        String target = requiredText(event.retractsEventId(), "retractsEventId");
        String logicalTarget = logicalEventId(target);
        String targetTenure = canonicalTenure(target);
        List<OccurrenceRow> occurrences = jdbcTemplate.query("""
                SELECT id, current_finality, token_transfer_id
                  FROM chain_event_occurrence
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND logical_event_id = ?
                   AND canonical_tenure = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new OccurrenceRow(
                        rs.getObject("id", UUID.class),
                        FinalityLevel.valueOf(rs.getString("current_finality")),
                        rs.getObject("token_transfer_id", UUID.class)),
                event.durabilityDomainId(), chainConfigId, logicalTarget, targetTenure);
        for (OccurrenceRow occurrence : occurrences) {
            if (occurrence.finality() == FinalityLevel.FINALIZED) {
                throw new ChaincacheProtocolException(
                        "Chaincache attempted to retract finalized log occurrence " + target);
            }
            if (occurrence.finality() == FinalityLevel.ORPHANED) {
                continue;
            }
            jdbcTemplate.update("""
                    UPDATE chain_event_occurrence
                       SET current_finality = 'ORPHANED', canonical = FALSE,
                           last_event_id = ?, updated_at = NOW()
                     WHERE id = ?
                    """, event.eventId(), occurrence.id());
            if (occurrence.tokenTransferId() != null) {
                logProjection.orphan(occurrence.tokenTransferId());
            }
        }
        // Block-level legacy corrections are retained for compatibility. Typed REORG events own
        // exact lineage compensation; a correction carrying reorgId is audit-only here.
        JsonNode payload = event.payload();
        if (target.startsWith("block:") && (payload == null || !payload.hasNonNull("reorgId"))) {
            long orphaned = requiredBlockNumber(event);
            long ancestor = payload == null ? orphaned - 1 : payload.path("commonAncestor").asLong(orphaned - 1);
            finalityFeed.recordRetraction(chainConfigId, ancestor + 1, null, 0);
        }
    }

    private void applyReorg(UUID chainConfigId, Envelope event) {
        JsonNode reorg = requireObject(event.reorg(), "REORG envelope");
        String schemaVersion = requiredText(reorg.path("schemaVersion").asText(null), "reorg.schemaVersion");
        String reorgId = requiredText(reorg.path("reorgId").asText(null), "reorg.reorgId");
        String envelopeChainKey = reorg.path("chainKey").path("value").asText(null);
        if (envelopeChainKey == null) {
            envelopeChainKey = reorg.path("chainKey").asText(null);
        }
        if (!event.chainKey().equals(envelopeChainKey)) {
            throw new ChaincacheProtocolException("REORG chainKey does not match lifecycle envelope");
        }
        ReorgObservation.BlockReference ancestor = reorg.path("commonAncestor").isObject()
                ? blockReference(reorg.path("commonAncestor")) : null;
        ReorgObservation observation = new ReorgObservation(schemaVersion, reorgId,
                ReorgObservation.ReorgSeverity.valueOf(requiredText(
                        reorg.path("severity").asText(null), "reorg.severity")),
                ancestor, blockReferences(reorg.path("orphanedLineage")),
                blockReferences(reorg.path("replacementLineage")),
                Instant.parse(requiredText(reorg.path("observedAt").asText(null), "reorg.observedAt")));
        try {
            reorgCoordinator.apply(chainConfigId, observation);
        } catch (TypedReorgCompensationException failure) {
            finalityFeed.recordReorg(chainConfigId, observation, 0,
                    QuarantineTrigger.INDEXER_COMPENSATION_FAILED);
        }
    }

    private SubscriptionRow lockCursor(UUID chainConfigId, String consumerId, Envelope event) {
        jdbcTemplate.update("""
                INSERT INTO chain_contract_subscription
                  (durability_domain_id, chain_config_id, chain_key, consumer_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (durability_domain_id, chain_config_id, consumer_id) DO NOTHING
                """, event.durabilityDomainId(), chainConfigId, event.chainKey(), consumerId);
        return jdbcTemplate.queryForObject("""
                SELECT last_sequence, last_event_id
                  FROM chain_contract_subscription
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND consumer_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new SubscriptionRow(
                        (Long) rs.getObject("last_sequence"), rs.getString("last_event_id")),
                event.durabilityDomainId(), chainConfigId, consumerId);
    }

    private void advanceCursor(UUID chainConfigId, String consumerId, Envelope event) {
        jdbcTemplate.update("""
                UPDATE chain_contract_subscription
                   SET last_sequence = ?, last_event_id = ?, subscription_state = 'LIVE', updated_at = NOW()
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND consumer_id = ?
                """, event.sequence(), event.eventId(), event.durabilityDomainId(), chainConfigId, consumerId);
    }

    private void quarantineSubscription(UUID chainConfigId, String consumerId, Envelope event, String reason) {
        jdbcTemplate.update("""
                INSERT INTO chain_contract_subscription
                  (durability_domain_id, chain_config_id, chain_key, consumer_id, subscription_state)
                VALUES (?, ?, ?, ?, 'QUARANTINED')
                ON CONFLICT (durability_domain_id, chain_config_id, consumer_id) DO UPDATE
                  SET subscription_state = 'QUARANTINED', updated_at = NOW()
                """, event.durabilityDomainId(), chainConfigId, event.chainKey(), consumerId);
    }

    private InboxRow inbox(String domain, UUID chainConfigId, String eventId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, payload_hash, processing_state
                      FROM chaincache_event_inbox
                     WHERE durability_domain_id = ? AND chain_config_id = ? AND event_id = ?
                    """, (rs, rowNum) -> new InboxRow(rs.getObject("id", UUID.class),
                            rs.getString("payload_hash"), rs.getString("processing_state")),
                    domain, chainConfigId, eventId);
        } catch (EmptyResultDataAccessException impossible) {
            throw new IllegalStateException("Inbox insert was not visible", impossible);
        }
    }

    private void quarantineInbox(UUID inboxId, String error) {
        jdbcTemplate.update("""
                UPDATE chaincache_event_inbox
                   SET processing_state = 'QUARANTINED', last_error = ?, last_received_at = NOW(),
                       delivery_count = delivery_count + 1
                 WHERE id = ?
                """, error, inboxId);
    }

    private void recordRedelivery(UUID inboxId) {
        jdbcTemplate.update("""
                UPDATE chaincache_event_inbox
                   SET delivery_count = delivery_count + 1, last_received_at = NOW()
                 WHERE id = ?
                """, inboxId);
    }

    private OccurrenceRow findOccurrenceForUpdate(UUID chainConfigId, String blockHash,
            String txHash, int logIndex, String contract, String tenure) {
        List<OccurrenceRow> rows = jdbcTemplate.query("""
                SELECT id, current_finality, token_transfer_id
                  FROM chain_event_occurrence
                 WHERE chain_config_id = ? AND block_hash = ? AND transaction_hash = ?
                   AND log_index = ? AND contract_address = ? AND canonical_tenure = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new OccurrenceRow(rs.getObject("id", UUID.class),
                        FinalityLevel.valueOf(rs.getString("current_finality")),
                        rs.getObject("token_transfer_id", UUID.class)),
                chainConfigId, blockHash, txHash, logIndex, contract, tenure);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Envelope parse(JsonNode event, String expectedChainKey, String expectedDomain) {
        requireObject(event, "lifecycle event");
        String schema = requiredText(event.path("schemaVersion").asText(null), "schemaVersion");
        if (!"2".equals(schema)) {
            throw new ChaincacheProtocolException("Unsupported Chaincache lifecycle schema " + schema);
        }
        String domain = requiredText(event.path("durabilityDomainId").asText(null), "durabilityDomainId");
        String chainKey = requiredText(event.path("chainKey").asText(null), "chainKey");
        if (!expectedDomain.equals(domain) || !expectedChainKey.equals(chainKey)) {
            throw new ChaincacheProtocolException("Lifecycle durability domain or chain key mismatch");
        }
        JsonNode sequence = event.get("sequence");
        if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()
                || sequence.asLong() < 0) {
            throw new ChaincacheProtocolException("Lifecycle sequence must be a non-negative integer");
        }
        String finalityText = event.path("finality").asText(null);
        FinalityLevel finality = finalityText == null ? null : FinalityLevel.valueOf(finalityText);
        String eventId = requiredText(event.path("eventId").asText(null), "eventId");
        String tenure = event.path("canonicalTenure").asText(canonicalTenure(eventId));
        if (tenure.isBlank()) {
            tenure = "0";
        }
        return new Envelope(schema, domain, chainKey, sequence.asLong(),
                eventId,
                requiredText(event.path("kind").asText(null), "kind"), finality,
                event.hasNonNull("blockNumber") ? event.path("blockNumber").asLong() : null,
                event.path("blockHash").asText(null), event.get("payload"),
                event.path("retractsEventId").asText(null),
                Instant.parse(requiredText(event.path("createdAt").asText(null), "createdAt")),
                event.get("reorg"), tenure);
    }

    private static void validatePromotion(FinalityLevel current, FinalityLevel next, String eventId) {
        if (current == FinalityLevel.ORPHANED || next == FinalityLevel.ORPHANED
                || rank(next) < rank(current)) {
            throw new ChaincacheProtocolException("Impossible lifecycle transition " + current
                    + " -> " + next + " for " + eventId);
        }
    }

    private static int rank(FinalityLevel level) {
        return switch (level) {
            case PROVISIONAL -> 0;
            case SAFE -> 1;
            case FINALIZED -> 2;
            case ORPHANED -> -1;
        };
    }

    private static long requiredBlockNumber(Envelope event) {
        if (event.blockNumber() == null || event.blockNumber() < 0) {
            throw new IllegalArgumentException("Lifecycle event requires a non-negative blockNumber");
        }
        return event.blockNumber();
    }

    private static FinalityLevel requiredFinality(Envelope event) {
        if (event.finality() == null || event.finality() == FinalityLevel.ORPHANED) {
            throw new IllegalArgumentException("Lifecycle event requires a positive finality level");
        }
        return event.finality();
    }

    private static JsonNode requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return node;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required lifecycle field: " + name);
        }
        return value;
    }

    private static String nullableName(FinalityLevel level) {
        return level == null ? null : level.name();
    }

    private static String normalizeHex(String value) {
        return value.startsWith("0x") || value.startsWith("0X")
                ? "0x" + value.substring(2).toLowerCase(Locale.ROOT) : value;
    }

    private static int requiredHexInt(JsonNode node, String field) {
        Integer value = optionalHexInt(node, field);
        if (value == null || value < 0) {
            throw new IllegalArgumentException("Missing or invalid EVM " + field);
        }
        return value;
    }

    private static Integer optionalHexInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isIntegralNumber()) {
                return value.intValue();
            }
            String text = value.asText();
            return text.startsWith("0x") || text.startsWith("0X")
                    ? Integer.parseUnsignedInt(text.substring(2), 16) : Integer.parseInt(text);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid EVM " + field, invalid);
        }
    }

    private static String topicAddress(String topic) {
        String normalized = normalizeHex(requiredText(topic, "transfer address topic"));
        String digits = normalized.startsWith("0x") ? normalized.substring(2) : normalized;
        if (digits.length() != 64) {
            throw new IllegalArgumentException("Transfer address topic must contain 32 bytes");
        }
        return "0x" + digits.substring(24);
    }

    private static BigDecimal unsignedHex(String value) {
        String normalized = requiredText(value, "unsigned EVM quantity");
        String digits = normalized.startsWith("0x") || normalized.startsWith("0X")
                ? normalized.substring(2) : normalized;
        if (digits.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(new BigInteger(digits, 16));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid unsigned EVM quantity", invalid);
        }
    }

    static String logicalEventId(String eventId) {
        int reinstatement = eventId.indexOf(":r");
        if (reinstatement >= 0) {
            return eventId.substring(0, reinstatement);
        }
        String logical = eventId;
        String previous;
        do {
            previous = logical;
            logical = logical.replaceFirst("(?i)(:provisional|:safe|:finalized)$", "");
        } while (!logical.equals(previous));
        return logical;
    }

    static String canonicalTenure(String eventId) {
        int marker = eventId.indexOf(":r");
        if (marker < 0) {
            return "0";
        }
        int start = marker + 2;
        int end = eventId.indexOf(':', start);
        String tenure = end < 0 ? eventId.substring(start) : eventId.substring(start, end);
        if (tenure.isBlank()) {
            throw new ChaincacheProtocolException("Malformed reinstatement eventId " + eventId);
        }
        return tenure;
    }

    private static List<ReorgObservation.BlockReference> blockReferences(JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) {
            throw new IllegalArgumentException("Reorg lineage must be an array");
        }
        List<ReorgObservation.BlockReference> result = new ArrayList<>();
        nodes.forEach(node -> result.add(blockReference(node)));
        return List.copyOf(result);
    }

    private static ReorgObservation.BlockReference blockReference(JsonNode node) {
        return new ReorgObservation.BlockReference(
                node.path("blockNumber").asLong(-1),
                requiredText(node.path("blockHash").asText(null), "reorg.blockHash"),
                requiredText(node.path("parentHash").asText(null), "reorg.parentHash"),
                FinalityLevel.valueOf(requiredText(node.path("finality").asText(null), "reorg.finality")));
    }

    static String payloadHash(ObjectMapper objectMapper, JsonNode node) {
        Object value = objectMapper.convertValue(node, Object.class);
        return sha256(objectMapper.writeValueAsString(sort(value)));
    }

    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), sort(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ChaincacheLifecycleEventProcessor::sort).toList();
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Envelope(String schemaVersion, String durabilityDomainId, String chainKey,
            long sequence, String eventId, String kind, FinalityLevel finality, Long blockNumber,
            String blockHash, JsonNode payload, String retractsEventId, Instant createdAt,
            JsonNode reorg, String canonicalTenure) {}

    private record InboxRow(UUID id, String payloadHash, String state) {}

    private record SubscriptionRow(Long lastSequence, String lastEventId) {}

    private record OccurrenceRow(UUID id, FinalityLevel finality, UUID tokenTransferId) {}
}
