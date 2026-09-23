package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1-09: investors request straight on the vault, so {@code vault_request} rows must come from
 * the chain. A recorded {@code DepositRequested} log fixture is ingested into a PENDING row
 * (real schema, real repositories, real chain-effect journal) and the operator fulfil path then
 * finds it — before ingestion existed that lookup always 404'd.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("VaultRequestIngestionService — ERC-7540 request ingestion into vault_request")
class VaultRequestIngestionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @MockitoBean EvmContractService evmContractService;
    @MockitoBean EvmFinalityResolver finalityResolver;
    @MockitoBean DurableEvmTransactionGateway durableTransactions;
    @MockitoBean BlockchainTransactionService blockchainTransactionService;

    @Autowired VaultRequestIngestionService ingestion;
    @Autowired Erc7540AdminService adminService;
    @Autowired AssetDeploymentRepository deploymentRepository;
    @Autowired VaultRequestRepository vaultRequestRepository;
    @Autowired JdbcTemplate jdbc;

    private static final String CONTROLLER = "0x00000000000000000000000000000000000000c1";
    private static final String OWNER = "0x00000000000000000000000000000000000000a1";
    private static final String PAYER = "0x00000000000000000000000000000000000000b1";

    private UUID assetId;
    private UUID deploymentId;
    private UUID chainConfigId;
    private String vault;
    private Web3j web3j;
    private final List<EthLog.LogResult<?>> logs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assetId = UUID.randomUUID();
        deploymentId = UUID.randomUUID();
        chainConfigId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();
        vault = "0x" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 40);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO legal_entity (id, entity_number, type, current_name) VALUES (?, ?, 'ISSUER', 'Vault IT')",
                issuerId, "ISS-" + suffix);
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Vault IT Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chainConfigId, "vault-it-" + chainConfigId);
        jdbc.update("""
                INSERT INTO asset (id, asset_number, issuer_id, name, token_standard, status)
                VALUES (?, ?, ?, 'Vault IT Fund', 'ERC7540', 'ISSUED')
                """, assetId, "AST-" + suffix, issuerId);
        jdbc.update("""
                INSERT INTO asset_deployment (id, asset_id, chain, network, contract_address, deployment_status,
                                              chain_config_id, block_number)
                VALUES (?, ?, 'ETHEREUM', 'TESTNET', ?, 'CONFIRMED', ?, 100)
                """, deploymentId, assetId, vault, chainConfigId);

        web3j = mock(Web3j.class);
        when(evmContractService.evmClient(chainConfigId)).thenReturn(web3j);
        when(finalityResolver.finalizedHead(any(), eq(web3j))).thenReturn(Optional.of(150L));
        when(evmContractService.simulateRevert(any(), any(), any(Function.class))).thenReturn(Optional.empty());
        when(evmContractService.call(eq(web3j), eq(vault), any(Function.class))).thenAnswer(inv -> {
            Function fn = inv.getArgument(2);
            return switch (fn.getName()) {
                case "depositRequestPayer" -> List.<Type>of(new Address(PAYER));
                case "isFrozen" -> List.<Type>of(new Bool(false));
                default -> throw new IllegalArgumentException(fn.getName());
            };
        });

        EthLog ethLog = new EthLog();
        ethLog.setResult(logs);
        @SuppressWarnings("unchecked")
        Request<?, EthLog> logsRequest = mock(Request.class);
        when(logsRequest.send()).thenReturn(ethLog);
        doReturn(logsRequest).when(web3j).ethGetLogs(any());

        EthBlock.Block block = new EthBlock.Block();
        block.setTimestamp("0x" + Long.toHexString(1_760_000_000L));
        EthBlock ethBlock = new EthBlock();
        ethBlock.setResult(block);
        @SuppressWarnings("unchecked")
        Request<?, EthBlock> blockRequest = mock(Request.class);
        when(blockRequest.send()).thenReturn(ethBlock);
        doReturn(blockRequest).when(web3j).ethGetBlockByHash(any(), eq(false));
    }

    /** Recorded fixture shape of an eth_getLogs entry. */
    private EthLog.LogObject log(long block, String txHash, int logIndex, List<String> topics, String data) {
        EthLog.LogObject l = new EthLog.LogObject();
        l.setAddress(vault);
        l.setBlockNumber("0x" + Long.toHexString(block));
        l.setBlockHash("0x" + String.format("%064x", block));
        l.setTransactionHash(txHash);
        l.setLogIndex("0x" + Integer.toHexString(logIndex));
        l.setTopics(topics);
        l.setData(data);
        return l;
    }

    private static String topicOf(BigInteger value) {
        return Numeric.toHexStringWithPrefixZeroPadded(value, 64);
    }

    private static String topicOf(String address) {
        return "0x" + "0".repeat(24) + address.substring(2);
    }

    private EthLog.LogObject depositRequested(long requestId, long assets) {
        return log(120, "0x" + "d".repeat(64), 0,
                List.of(Erc7540Events.DEPOSIT_REQUESTED_TOPIC, topicOf(BigInteger.valueOf(requestId)),
                        topicOf(CONTROLLER), topicOf(OWNER)),
                "0x" + TypeEncoder.encode(new Uint256(assets)));
    }

    @Test
    @DisplayName("a DepositRequested log becomes a PENDING row the operator fulfil path then finds")
    void depositRequestIsIngestedAndFulfillable() throws Exception {
        logs.add(depositRequested(7, 500_000_000L));
        AssetDeployment dep = deploymentRepository.findById(deploymentId).orElseThrow();

        ingestion.ingestDeployment(dep);

        VaultRequest row = vaultRequestRepository.findByAssetIdAndRequestId(assetId, BigInteger.valueOf(7)).orElseThrow();
        assertThat(row.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(row.getRequestType()).isEqualTo(VaultRequestType.DEPOSIT);
        assertThat(row.getControllerAddr()).isEqualTo(CONTROLLER);
        assertThat(row.getOwnerAddr()).isEqualTo(OWNER);
        assertThat(row.getPayerAddr()).isEqualTo(PAYER);
        assertThat(row.getAssetAmount()).isEqualTo(BigInteger.valueOf(500_000_000L));
        assertThat(row.getRequestedBlockNumber()).isEqualTo(120L);
        assertThat(jdbc.queryForObject(
                "SELECT last_scanned_block FROM vault_request_ingest_cursor WHERE asset_deployment_id = ?",
                Long.class, deploymentId)).isEqualTo(150L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chain_effect WHERE effect_type = 'VAULT_REQUEST_INGESTED' AND entity_id = ?",
                Long.class, row.getId())).isEqualTo(1L);

        // Idempotent: re-reading the same range does not duplicate the row.
        jdbc.update("UPDATE vault_request_ingest_cursor SET last_scanned_block = 99 WHERE asset_deployment_id = ?",
                deploymentId);
        ingestion.ingestDeployment(dep);
        assertThat(vaultRequestRepository.findByAssetIdAndRequestStatus(assetId, VaultRequestStatus.PENDING)).hasSize(1);

        when(durableTransactions.submit(eq(chainConfigId), eq(vault), any(Function.class), any())).thenReturn("0xfulfil");
        when(blockchainTransactionService.record(eq("0xfulfil"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        adminService.fulfillRequest(deploymentId, BigInteger.valueOf(7), UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(durableTransactions).submit(eq(chainConfigId), eq(vault), fn.capture(), any());
        assertThat(fn.getValue().getName()).isEqualTo("fulfillDepositRequest");
        assertThat(vaultRequestRepository.findById(row.getId()).orElseThrow().getFulfilledTx()).isEqualTo("0xfulfil");
    }

    @Test
    @DisplayName("a controller's own on-chain cancel and a registry force-cancel resolve the ingested rows")
    void externalCancelAndForceCancelAreIngested() throws Exception {
        logs.add(depositRequested(8, 100L));
        logs.add(log(121, "0x" + "e".repeat(64), 0,
                List.of(Erc7540Events.REDEEM_REQUESTED_TOPIC, topicOf(BigInteger.valueOf(9)),
                        topicOf(CONTROLLER), topicOf(OWNER)),
                "0x" + TypeEncoder.encode(new Uint256(40L))));
        logs.add(log(130, "0x" + "f".repeat(64), 0,
                List.of(Erc7540Events.REQUEST_CANCELLED_TOPIC, topicOf(BigInteger.valueOf(8)), topicOf(CONTROLLER)),
                "0x"));
        String legalBasisData = org.web3j.abi.FunctionEncoder.encodeConstructor(
                List.of(new org.web3j.abi.datatypes.Utf8String("LG Frankfurt 2-04 O 1/26")));
        logs.add(log(131, "0x" + "a".repeat(64), 1,
                List.of(Erc7540Events.FORCED_REQUEST_CANCELLED_TOPIC, topicOf(BigInteger.valueOf(9)),
                        topicOf("0x00000000000000000000000000000000000000ee")),
                "0x" + legalBasisData));

        ingestion.ingestDeployment(deploymentRepository.findById(deploymentId).orElseThrow());

        VaultRequest cancelled = vaultRequestRepository.findByAssetIdAndRequestId(assetId, BigInteger.valueOf(8)).orElseThrow();
        assertThat(cancelled.getRequestStatus()).isEqualTo(VaultRequestStatus.CANCELLED);
        assertThat(cancelled.isConfirmed()).isTrue();
        assertThat(cancelled.getCancelledTx()).isEqualTo("0x" + "f".repeat(64));

        VaultRequest forced = vaultRequestRepository.findByAssetIdAndRequestId(assetId, BigInteger.valueOf(9)).orElseThrow();
        assertThat(forced.getRequestStatus()).isEqualTo(VaultRequestStatus.FORCE_CANCELLED);
        assertThat(forced.getForcedToAddr()).isEqualTo("0x00000000000000000000000000000000000000ee");
        assertThat(forced.getLegalBasis()).isEqualTo("LG Frankfurt 2-04 O 1/26");
        assertThat(forced.getShareAmount()).isEqualTo(BigInteger.valueOf(40L));
    }
}
