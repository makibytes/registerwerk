package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture based tests of the Solana balance-delta decoder (P4-02 / P4D-2): realistic
 * {@code getTransaction} {@code jsonParsed} results for SPL Token and Token-2022, including a
 * batched two-transfer transaction, mintTo, a transfer-fee transfer and legacy data without owner.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SolanaTransferSyncService - balance-delta decoding")
class SolanaTransferSyncServiceTest {

    private static final String MINT = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ALICE = "OwnerAlice1111111111111111111111111111111111";
    private static final String BOB = "OwnerBob111111111111111111111111111111111111";
    private static final String CAROL = "OwnerCarol11111111111111111111111111111111111";

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private SolanaMintSyncCursorRepository mintSyncCursorRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private IndexerSyncSupport syncSupport;

    private final ObjectMapper mapper = new ObjectMapper();
    private SolanaTransferSyncService service;
    private ChainConfig chain;
    private AssetDeployment deployment;

    @BeforeEach
    void setUp() {
        service = new SolanaTransferSyncService(chainConfigRepository, indexerStateRepository, tokenTransferRepository,
                mintSyncCursorRepository, assetDeploymentRepository, syncSupport, new ExplorerUrlBuilder(),
                RestClient.builder());
        chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("SOLANA_TESTNET");
        chain.setChainType(ChainConfig.ChainType.SOLANA);
        deployment = new AssetDeployment();
        deployment.setId(UUID.randomUUID());
        deployment.setAssetId(UUID.randomUUID());
        deployment.setContractAddress(MINT);
    }

    private Map<String, Object> fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/solana/" + name)) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    private List<TokenTransfer> decode(String fixture, String signature) throws IOException {
        return service.toTransfers(chain, deployment, signature, Map.of("signature", signature), fixture(fixture));
    }

    @Test
    @DisplayName("transferChecked on spl-token books one TRANSFER between the two OWNERS with amount, deployment, slot and blockTime")
    void transferChecked() throws IOException {
        List<TokenTransfer> rows = decode("transferchecked-spl-token.json", "sigTransferChecked");

        assertThat(rows).hasSize(1); // the unrelated mint's balance is ignored
        TokenTransfer t = rows.get(0);
        assertThat(t.getEventType()).isEqualTo(TokenTransfer.EventType.TRANSFER);
        assertThat(t.getFromAddress()).isEqualTo(ALICE);
        assertThat(t.getToAddress()).isEqualTo(BOB);
        assertThat(t.getAmount()).isEqualByComparingTo("400");
        assertThat(t.getContractAddress()).isEqualTo(MINT);
        assertThat(t.getDeploymentId()).isEqualTo(deployment.getId());
        assertThat(t.getAssetId()).isEqualTo(deployment.getAssetId());
        assertThat(t.getSlot()).isEqualTo(310000001L);
        assertThat(t.getBlockNumber()).isEqualTo(310000001L);
        assertThat(t.getLogIndex()).isZero();
        assertThat(t.getOccurredAt()).isEqualTo(Instant.ofEpochSecond(1767225600L));
        assertThat(t.getTxHash()).isEqualTo("sigTransferChecked");
    }

    @Test
    @DisplayName("a Token-2022 batch with two transfers books two rows with distinct log indexes and 18-decimal raw amounts")
    void batchTwoTransfersToken2022() throws IOException {
        List<TokenTransfer> rows = decode("batch-two-transfers-token2022.json", "sigBatch");

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(TokenTransfer::getLogIndex).containsExactly(0, 1);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEventType()).isEqualTo(TokenTransfer.EventType.TRANSFER);
            assertThat(r.getFromAddress()).isEqualTo(ALICE);
            assertThat(r.getSlot()).isEqualTo(310000050L);
        });
        // Largest counterparty first; amounts are raw base units and net to Alice's -150 * 10^18.
        assertThat(rows.get(0).getToAddress()).isEqualTo(BOB);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("100000000000000000000"));
        assertThat(rows.get(1).getToAddress()).isEqualTo(CAROL);
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("50000000000000000000"));
    }

    @Test
    @DisplayName("mintTo with no counterparty is a MINT to the owner")
    void mintTo() throws IOException {
        List<TokenTransfer> rows = decode("mint-to.json", "sigMint");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(TokenTransfer.EventType.MINT);
        assertThat(rows.get(0).getFromAddress()).isNull();
        assertThat(rows.get(0).getToAddress()).isEqualTo(BOB);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("a Token-2022 transfer fee is the balance delta: TRANSFER of the received part plus a small BURN")
    void transferFeeShowsAsBurn() throws IOException {
        List<TokenTransfer> rows = decode("token2022-transfer-fee.json", "sigFee");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEventType()).isEqualTo(TokenTransfer.EventType.TRANSFER);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("98");
        assertThat(rows.get(1).getEventType()).isEqualTo(TokenTransfer.EventType.BURN);
        assertThat(rows.get(1).getFromAddress()).isEqualTo(ALICE);
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("legacy balances without owner fall back to the token account address and book a BURN")
    void burnWithoutOwner() throws IOException {
        List<TokenTransfer> rows = decode("burn-no-owner.json", "sigBurn");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(TokenTransfer.EventType.BURN);
        assertThat(rows.get(0).getFromAddress()).isEqualTo("LegacyTokenAccountAaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(rows.get(0).getToAddress()).isNull();
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a transaction whose meta.err is set books nothing")
    void failedTransactionBooksNothing() throws IOException {
        Map<String, Object> tx = fixture("transferchecked-spl-token.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) tx.get("meta");
        meta.put("err", Map.of("InstructionError", List.of(0, "Custom")));

        assertThat(service.toTransfers(chain, deployment, "sigFailed", Map.of(), tx)).isEmpty();
    }

    @Test
    @DisplayName("a result without any block time is refused instead of being stamped with processing time")
    void missingBlockTimeIsRefused() throws IOException {
        Map<String, Object> tx = fixture("transferchecked-spl-token.json");
        tx.remove("blockTime");

        assertThatThrownBy(() -> service.toTransfers(chain, deployment, "sigNoTime", Map.of(), tx))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("blockTime");
    }
}
