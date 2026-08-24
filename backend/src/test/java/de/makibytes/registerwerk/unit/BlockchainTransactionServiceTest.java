package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.blockchain.events.BlockchainTxReviewedEvent;
import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionCompletionWriter;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the confirmation-depth / finality logic added to guard against
 * reorgs: a mined-but-shallow transaction must stay PENDING (never TIMEOUT, never
 * SUCCESS/FAILED) until it clears the configured confirmation depth.
 */
@ExtendWith(MockitoExtension.class)
class BlockchainTransactionServiceTest {

    @Mock
    private BlockchainTransactionRepository repository;

    @Mock
    private BlockchainClientRegistry clientRegistry;

    @Mock
    private EvmContractService evmContractService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Web3j web3j;

    @Mock
    private ChainConfigRepository chainConfigRepository;

    @Mock
    private de.makibytes.registerwerk.finality.api.ChainEffectRecorder chainEffectRecorder;

    private BlockchainTxProperties txProperties;
    private BlockchainTransactionService service;

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(12);
        txProperties.setTimeoutSeconds(900);
        // Real (non-mocked) writer, wired to the same repository/eventPublisher mocks — mirrors
        // AssetDeploymentCompletionWriterTest's pattern. complete()/markTimeout() used to be
        // self-invoked @Transactional methods on this service (a no-op due to Spring proxy
        // bypass); they now live on this separate bean so the transaction boundary is real.
        BlockchainTransactionCompletionWriter completionWriter =
                new BlockchainTransactionCompletionWriter(repository, eventPublisher, new SimpleMeterRegistry(), chainEffectRecorder);
        // chainConfigRepository is left unstubbed in most tests: findByIdentifier() then returns
        // Optional.empty() (Mockito's built-in default for Optional-returning methods), and
        // EvmFinalityResolver.resolveModel() falls back to DEPTH_BASED — i.e. the pre-existing
        // depth-only behavior every other test in this class exercises. The same unstubbed
        // Optional.empty() also means BlockchainTransactionService.record() never resolves a
        // chainConfigId in these tests, so completionWriter.complete()'s chain-effect journalling
        // is a no-op (chainConfigId stays null) — chainEffectRecorder needs no stubbing either.
        EvmFinalityResolver finalityResolver = new EvmFinalityResolver(chainConfigRepository, txProperties);
        service = new BlockchainTransactionService(repository, clientRegistry, evmContractService,
                eventPublisher, txProperties, completionWriter, finalityResolver, chainConfigRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static BlockchainTransaction pendingTx(String txHash, String chain, String network, Instant createdAt) {
        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setTxHash(txHash);
        tx.setChain(chain);
        tx.setNetwork(network);
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        ReflectionTestUtils.setField(tx, "createdAt", createdAt);
        return tx;
    }

    private void stubPending(BlockchainTransaction... txs) {
        when(repository.findByStatus(BlockchainTransaction.Status.PENDING)).thenReturn(List.of(txs));
    }

    // ── pollPendingTransactions — no hash/chain yet ──────────────────────────────

    @Test
    void pollPendingTransactions_noPending_doesNothing() {
        stubPending();
        service.pollPendingTransactions();
        verifyNoInteractions(clientRegistry);
    }

    @Test
    void pollPendingTransactions_missingHashOrChain_notTimedOut_staysPending() {
        BlockchainTransaction tx = pendingTx(null, null, null, Instant.now());
        stubPending(tx);

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    void pollPendingTransactions_missingHashOrChain_timedOut_marksTimeout() {
        BlockchainTransaction tx = pendingTx(null, null, null, Instant.now().minusSeconds(1_000));
        stubPending(tx);

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.TIMEOUT);
        assertThat(tx.getErrorMessage()).contains("900s");
        verify(repository).save(tx);
    }

    // ── pollPendingTransactions — mined but not yet found ────────────────────────

    @Test
    void pollPendingTransactions_noReceiptYet_notTimedOut_staysPending() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.empty());

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("resolves the client via the node-pool-aware identifier lookup "
            + "(not the legacy descriptor tier, which bypasses multi-node failover entirely)")
    void pollPendingTransactions_resolvesClientViaNodePoolAwareIdentifier() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.empty());

        service.pollPendingTransactions();

        verify(clientRegistry).getEvmClientByIdentifier("ETHEREUM_MAINNET");
        verify(clientRegistry, never()).getEvmClient(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class));
    }

    @Test
    void pollPendingTransactions_noReceiptYet_timedOut_marksTimeout() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now().minusSeconds(1_000));
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.empty());

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.TIMEOUT);
    }

    // ── pollPendingTransactions — confirmation depth (reorg guard) ───────────────

    @Test
    void pollPendingTransactions_minedButBelowConfirmationDepth_staysPendingAndIsNeverTimedOut() throws java.io.IOException {
        // Old enough to time out if the (buggy) code ever applied the timeout to a mined tx.
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now().minusSeconds(1_000));
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setBlockHash("0xblock100a");
        receipt.setStatus("0x1");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        // current=105 -> depth = 105-100+1 = 6, below the default 12 required.
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(105));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        // Not yet SUCCESS/FAILED/TIMEOUT — but the block hash/number seen this poll IS persisted
        // (reorg-guard baseline for the next poll's mismatch check), which is a real save.
        assertThat(tx.getBlockNumber()).isEqualTo(100L);
        assertThat(tx.getBlockHash()).isEqualTo("0xblock100a");
        verify(repository).save(tx);
    }

    @Test
    void pollPendingTransactions_secondPollSameBlock_doesNotResaveUnnecessarily() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        ReflectionTestUtils.setField(tx, "blockNumber", 100L);
        ReflectionTestUtils.setField(tx, "blockHash", "0xblock100a");
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100a"); // unchanged from the previous poll
        receipt.setStatus("0x1");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(105));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    void pollPendingTransactions_blockHashMismatch_resetsToPendingNotFailed() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        ReflectionTestUtils.setField(tx, "blockNumber", 100L);
        ReflectionTestUtils.setField(tx, "blockHash", "0xblock100a");
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64");
        receipt.setBlockHash("0xblock100b"); // reorged — different hash at the same height
        receipt.setStatus("0x1");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        assertThat(tx.getBlockHash()).isNull();
        assertThat(tx.getBlockNumber()).isNull();
        verify(repository).save(tx);
        // Never reaches confirmations()/ethBlockNumber() — the mismatch short-circuits first.
        verify(web3j, never()).ethBlockNumber();
    }

    @Test
    void pollPendingTransactions_minedAndAtExactConfirmationThreshold_success() throws java.io.IOException {
        UUID deploymentId = UUID.randomUUID();
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        tx.setDeploymentId(deploymentId);
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x5208"); // 21000
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        // current=111 -> depth = 111-100+1 = 12, exactly the default required.
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(111));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.SUCCESS);
        assertThat(tx.getBlockNumber()).isEqualTo(100L);
        assertThat(tx.getGasUsed()).isEqualTo(21_000L);
        verify(repository).save(tx);
        verify(eventPublisher).publishEvent(any(BlockchainTxStatusEvent.class));
    }

    @Test
    void pollPendingTransactions_minedAndConfirmed_revertedOnChain_marksFailed() throws java.io.IOException {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64");
        receipt.setStatus("0x0"); // reverted
        receipt.setGasUsed("0x5208"); // getGasUsed() throws if the raw field is left unset
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(200));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.FAILED);
        assertThat(tx.getErrorMessage()).isEqualTo("Transaction reverted on-chain");
    }

    @Test
    void pollPendingTransactions_perChainConfirmationOverrideIsRespected() throws java.io.IOException {
        txProperties.setConfirmationsByChain(Map.of("POLYGON", 128));
        BlockchainTransaction tx = pendingTx("0xabc", "POLYGON", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setStatus("0x1");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        // 30 confirmations: comfortably past the 12-block default, nowhere near Polygon's 128.
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(100 + 30 - 1));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
    }

    // ── pollPendingTransactions — per-chain FinalityModel ────────────────────────

    @Test
    @DisplayName("TAG_BASED chain: mined but below the node's finalized tag stays PENDING, "
            + "never consulting the depth heuristic")
    void pollPendingTransactions_tagBased_belowFinalizedTag_staysPending() throws java.io.IOException {
        ChainConfig chain = new ChainConfig();
        chain.setFinalityModel(ChainConfig.FinalityModel.TAG_BASED);
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain));

        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setStatus("0x1");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        org.web3j.protocol.core.methods.response.EthBlock finalizedResponse =
                mock(org.web3j.protocol.core.methods.response.EthBlock.class, Answers.RETURNS_DEEP_STUBS);
        when(finalizedResponse.hasError()).thenReturn(false);
        when(finalizedResponse.getBlock().getNumber()).thenReturn(BigInteger.valueOf(90)); // finalized < 100
        when(web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.FINALIZED, false)
                .send()).thenReturn(finalizedResponse);

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        // TAG_BASED never calls ethBlockNumber() — the depth heuristic is bypassed entirely.
        verify(web3j, never()).ethBlockNumber();
    }

    @Test
    @DisplayName("TAG_BASED chain: mined at or below the node's finalized tag completes, "
            + "even though depth alone would still be shallow")
    void pollPendingTransactions_tagBased_atFinalizedTag_completes() throws java.io.IOException {
        ChainConfig chain = new ChainConfig();
        chain.setFinalityModel(ChainConfig.FinalityModel.TAG_BASED);
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain));

        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64"); // block 100
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x5208");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        org.web3j.protocol.core.methods.response.EthBlock finalizedResponse =
                mock(org.web3j.protocol.core.methods.response.EthBlock.class, Answers.RETURNS_DEEP_STUBS);
        when(finalizedResponse.hasError()).thenReturn(false);
        when(finalizedResponse.getBlock().getNumber()).thenReturn(BigInteger.valueOf(100)); // finalized == 100
        when(web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.FINALIZED, false)
                .send()).thenReturn(finalizedResponse);

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.SUCCESS);
    }

    @Test
    @DisplayName("INSTANT chain (permissioned BFT): completes on the first receipt, "
            + "no depth or tag lookup at all")
    void pollPendingTransactions_instant_completesImmediately() throws java.io.IOException {
        ChainConfig chain = new ChainConfig();
        chain.setFinalityModel(ChainConfig.FinalityModel.INSTANT);
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain));

        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        stubPending(tx);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenReturn(web3j);

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setBlockNumber("0x64");
        receipt.setStatus("0x1");
        receipt.setGasUsed("0x5208");
        when(web3j.ethGetTransactionReceipt("0xabc").send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));

        service.pollPendingTransactions();

        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.SUCCESS);
        verify(web3j, never()).ethBlockNumber();
        verify(web3j, never()).ethGetBlockByNumber(any(), anyBoolean());
    }

    @Test
    void pollPendingTransactions_exceptionForOneTx_doesNotAbortRemaining() {
        BlockchainTransaction broken = pendingTx("0xbad", "ETHEREUM", "MAINNET", Instant.now());
        BlockchainTransaction fine = pendingTx(null, null, null, Instant.now().minusSeconds(1_000));
        stubPending(broken, fine);
        when(clientRegistry.getEvmClientByIdentifier(any(String.class))).thenThrow(new RuntimeException("RPC down"));

        service.pollPendingTransactions();

        // "broken" blew up inside the per-tx try/catch and is left untouched...
        assertThat(broken.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        // ...but the loop still reached "fine" (no hash/chain -> never calls getEvmClient).
        assertThat(fine.getStatus()).isEqualTo(BlockchainTransaction.Status.TIMEOUT);
    }

    // ── isConfirmedSuccess / isConfirmedFailure ──────────────────────────────────

    @Test
    void isConfirmedSuccess_trueOnlyForTrackedSuccessRow() {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        tx.setStatus(BlockchainTransaction.Status.SUCCESS);
        when(repository.findByTxHash("0xabc")).thenReturn(Optional.of(tx));

        assertThat(service.isConfirmedSuccess("0xabc")).isTrue();
        assertThat(service.isConfirmedFailure("0xabc")).isFalse();
    }

    @Test
    void isConfirmedSuccess_falseForStillPendingRow() {
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        when(repository.findByTxHash("0xabc")).thenReturn(Optional.of(tx));

        assertThat(service.isConfirmedSuccess("0xabc")).isFalse();
        assertThat(service.isConfirmedFailure("0xabc")).isFalse();
    }

    @Test
    void isConfirmedSuccess_falseForUntrackedHash() {
        when(repository.findByTxHash("0xunknown")).thenReturn(Optional.empty());

        assertThat(service.isConfirmedSuccess("0xunknown")).isFalse();
        assertThat(service.isConfirmedFailure("0xunknown")).isFalse();
    }

    @Test
    void isConfirmedFailure_trueForFailedAndTimedOutRows() {
        BlockchainTransaction failed = pendingTx("0xfailed", "ETHEREUM", "MAINNET", Instant.now());
        failed.setStatus(BlockchainTransaction.Status.FAILED);
        BlockchainTransaction timedOut = pendingTx("0xtimeout", "ETHEREUM", "MAINNET", Instant.now());
        timedOut.setStatus(BlockchainTransaction.Status.TIMEOUT);
        when(repository.findByTxHash("0xfailed")).thenReturn(Optional.of(failed));
        when(repository.findByTxHash("0xtimeout")).thenReturn(Optional.of(timedOut));

        assertThat(service.isConfirmedFailure("0xfailed")).isTrue();
        assertThat(service.isConfirmedFailure("0xtimeout")).isTrue();
        assertThat(service.isConfirmedSuccess("0xfailed")).isFalse();
    }

    // ── record ────────────────────────────────────────────────────────────────

    @Test
    void record_createsPendingTransactionWithActorFromSecurityContext() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(repository.save(any(BlockchainTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        service.record("0xabc", "pause", deploymentId, assetId, "ETHEREUM", "MAINNET", "0xcontract", Map.of("k", "v"));

        ArgumentCaptor<BlockchainTransaction> captor = ArgumentCaptor.forClass(BlockchainTransaction.class);
        verify(repository).save(captor.capture());
        BlockchainTransaction tx = captor.getValue();
        assertThat(tx.getTxHash()).isEqualTo("0xabc");
        assertThat(tx.getStatus()).isEqualTo(BlockchainTransaction.Status.PENDING);
        assertThat(tx.getActorName()).isEqualTo("alice");
        assertThat(tx.getActorRole()).isEqualTo("REGISTRY_ADMIN");
        assertThat(tx.getDeploymentId()).isEqualTo(deploymentId);
        assertThat(tx.getAssetId()).isEqualTo(assetId);
    }

    @Test
    void record_resolvesChainConfigIdWhenTheChainNetworkPairIsKnown() {
        UUID chainConfigId = UUID.randomUUID();
        ChainConfig chain = new ChainConfig();
        ReflectionTestUtils.setField(chain, "id", chainConfigId);
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain));
        when(repository.save(any(BlockchainTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record("0xabc", "pause", null, null, "ETHEREUM", "MAINNET", "0xcontract", Map.of());

        ArgumentCaptor<BlockchainTransaction> captor = ArgumentCaptor.forClass(BlockchainTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isEqualTo(chainConfigId);
    }

    @Test
    void record_unresolvableChainNetworkPair_leavesChainConfigIdNull() {
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.empty());
        when(repository.save(any(BlockchainTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record("0xabc", "pause", null, null, "ETHEREUM", "MAINNET", "0xcontract", Map.of());

        ArgumentCaptor<BlockchainTransaction> captor = ArgumentCaptor.forClass(BlockchainTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isNull();
    }

    @Test
    void record_noAuthentication_defaultsToSystemActorAndUnknownRole() {
        when(repository.save(any(BlockchainTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record("0xabc", "pause", null, null, "ETHEREUM", "MAINNET", "0xcontract", Map.of());

        ArgumentCaptor<BlockchainTransaction> captor = ArgumentCaptor.forClass(BlockchainTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorName()).isEqualTo("system");
        assertThat(captor.getValue().getActorRole()).isEqualTo("UNKNOWN");
    }

    // ── review ────────────────────────────────────────────────────────────────

    @Test
    void review_failedTransaction_recordsNoteAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        tx.setStatus(BlockchainTransaction.Status.FAILED);
        when(repository.findById(id)).thenReturn(Optional.of(tx));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlockchainTransaction reviewed = service.review(id, actorId, "REGISTRY_ADMIN", "Resubmitted manually.");

        assertThat(reviewed.getOpsNote()).isEqualTo("Resubmitted manually.");
        assertThat(reviewed.getOpsReviewedBy()).isEqualTo(actorId);
        assertThat(reviewed.getOpsReviewedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(BlockchainTxReviewedEvent.class));
    }

    @Test
    void review_timeoutTransaction_isAccepted() {
        UUID id = UUID.randomUUID();
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        tx.setStatus(BlockchainTransaction.Status.TIMEOUT);
        when(repository.findById(id)).thenReturn(Optional.of(tx));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlockchainTransaction reviewed = service.review(id, UUID.randomUUID(), "AUDIT", "Confirmed stuck, no action needed.");

        assertThat(reviewed.getOpsNote()).isEqualTo("Confirmed stuck, no action needed.");
    }

    @Test
    void review_pendingTransaction_rejected() {
        UUID id = UUID.randomUUID();
        BlockchainTransaction tx = pendingTx("0xabc", "ETHEREUM", "MAINNET", Instant.now());
        when(repository.findById(id)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> service.review(id, UUID.randomUUID(), "REGISTRY_ADMIN", "note"))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void review_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(id, UUID.randomUUID(), "REGISTRY_ADMIN", "note"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
