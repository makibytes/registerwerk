package de.makibytes.registerwerk.blockchain.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import de.makibytes.registerwerk.TestPostgres;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exactly-once core: {@link ChaincacheLifecycleEventProcessor} is Registerwerk's transactional
 * inbox boundary for the Chaincache v2 lifecycle stream — this is where at-least-once transport
 * delivery is turned into exactly-once local effects (see the class javadoc). Uses BLOCK events
 * throughout (not LOG/Transfer projection) to keep fixture setup to a single {@code chain_config}
 * row while still exercising the real dedup/gap/quarantine logic every event kind shares.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ChaincacheLifecycleEventProcessorIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ChaincacheLifecycleEventProcessor processor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private UUID chainConfigId;
    private static final String DOMAIN = "domain-it";
    private static final String CHAIN_KEY = "sepolia";
    private static final String CONSUMER = "registerwerk:it:sepolia";

    @BeforeEach
    void seedChain() {
        chainConfigId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'IT Sepolia', 'EVM', 'TESTNET', 'http://localhost:1', true)
                """, chainConfigId, "it-sepolia-" + chainConfigId);
    }

    @Test
    void exactRedeliveryOfAProcessedEventIsANoOpAck() {
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));

        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));

        assertThat(deliveryCount("0xa")).isEqualTo(2);
        assertThat(lastSequence()).isEqualTo(1);
    }

    @Test
    void replayOfAnAlreadyProcessedEventBehindTheCursorIsANoOpAckNotCorruption() {
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(2, "0xb", 101));
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(3, "0xc", 102));

        // A redelivery of sequence 1 arrives after the cursor has already advanced to 3 - e.g. a
        // lease takeover mid-batch, or Chaincache's own cursor restore. Must not be treated as the
        // "behind the cursor" version of corruption the way a *forward* gap is.
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));

        assertThat(lastSequence()).isEqualTo(3);
        assertThat(subscriptionState()).isEqualTo("LIVE");
        assertThat(deliveryCount("0xa")).isEqualTo(2);
    }

    @Test
    void aForwardSequenceGapQuarantinesBothInboxAndSubscription() {
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));

        assertThatThrownBy(() ->
                processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(3, "0xc", 102)))
                .isInstanceOf(ChaincacheProtocolException.class)
                .hasMessageContaining("gap");

        assertThat(subscriptionState()).isEqualTo("QUARANTINED");
        assertThat(inboxState("block:0xc:provisional")).isEqualTo("QUARANTINED");
        // The cursor must not have moved past the last genuinely applied event.
        assertThat(lastSequence()).isEqualTo(1);
    }

    @Test
    void anEventIdReusedWithADifferentPayloadQuarantinesInsteadOfSilentlyOverwriting() {
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));

        JsonNode conflicting = blockEvent(2, "0xa", 999); // same eventId ("block:0xa:provisional"), different payload

        assertThatThrownBy(() ->
                processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, conflicting))
                .isInstanceOf(ChaincacheProtocolException.class)
                .hasMessageContaining("Conflicting");

        assertThat(inboxState("block:0xa:provisional")).isEqualTo("QUARANTINED");
        assertThat(subscriptionState()).isEqualTo("QUARANTINED");
    }

    @Test
    void aQuarantinedInboxEventRefusesFurtherProcessingUntilExplicitlyCleared() {
        processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(1, "0xa", 100));
        assertThatThrownBy(() ->
                processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(3, "0xc", 102)))
                .isInstanceOf(ChaincacheProtocolException.class);

        assertThatThrownBy(() ->
                processor.process(chainConfigId, CONSUMER, CHAIN_KEY, DOMAIN, blockEvent(3, "0xc", 102)))
                .isInstanceOf(ChaincacheProtocolException.class)
                .hasMessageContaining("quarantined");
    }

    /** A genuine redelivery of an immutable Chaincache event carries byte-identical content,
     *  including {@code createdAt} - so this deliberately derives a fixed timestamp from the
     *  event's own identity rather than calling {@code Instant.now()}, which would make two
     *  redeliveries of "the same" event fail the payload-hash check as if they were a conflicting
     *  eventId reuse. */
    private JsonNode blockEvent(long sequence, String blockHash, long blockNumber) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(blockNumber);
        String json = """
                {"schemaVersion":"2","durabilityDomainId":"%s","chainKey":"%s","sequence":%d,
                 "eventId":"block:%s:provisional","kind":"BLOCK","finality":"PROVISIONAL",
                 "blockNumber":%d,"blockHash":"%s","payload":{},"retractsEventId":null,
                 "createdAt":"%s"}
                """.formatted(DOMAIN, CHAIN_KEY, sequence, blockHash, blockNumber, blockHash, createdAt);
        return objectMapper.readTree(json);
    }

    private long deliveryCount(String blockHash) {
        return jdbc.queryForObject("""
                SELECT delivery_count FROM chaincache_event_inbox
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND event_id = ?
                """, Long.class, DOMAIN, chainConfigId, "block:" + blockHash + ":provisional");
    }

    private String inboxState(String eventId) {
        return jdbc.queryForObject("""
                SELECT processing_state FROM chaincache_event_inbox
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND event_id = ?
                """, String.class, DOMAIN, chainConfigId, eventId);
    }

    private Long lastSequence() {
        return jdbc.queryForObject("""
                SELECT last_sequence FROM chain_contract_subscription
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND consumer_id = ?
                """, Long.class, DOMAIN, chainConfigId, CONSUMER);
    }

    private String subscriptionState() {
        return jdbc.queryForObject("""
                SELECT subscription_state FROM chain_contract_subscription
                 WHERE durability_domain_id = ? AND chain_config_id = ? AND consumer_id = ?
                """, String.class, DOMAIN, chainConfigId, CONSUMER);
    }
}
