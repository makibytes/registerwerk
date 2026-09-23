package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingests ERC-7540 request lifecycles from the chain into {@code vault_request} (review finding
 * T1-09). Investors call {@code requestDeposit}/{@code requestRedeem} directly on the vault, so
 * without this nothing ever created the rows {@link Erc7540AdminService} looks requests up by —
 * the operator fulfil/cancel queue was unreachable.
 *
 * <p>Per confirmed ERC-7540 deployment, scans the vault's own logs over RPC ({@code eth_getLogs})
 * from a durable cursor ({@link VaultRequestIngestCursor}, first run: the deployment block) up to
 * the chain's current FINALIZED head only — so a row is never created from history that can
 * still be routinely reorged away. Each range's rows and the cursor advance commit together.
 *
 * <ul>
 *   <li>{@code DepositRequested}/{@code RedeemRequested} — upserts a PENDING row on
 *       {@code (asset_id, request_id)}, incl. the deposit payer ({@code depositRequestPayer}),
 *       and journals {@code VAULT_REQUEST_INGESTED} (see {@link VaultRequestIngestRevertCompensator}).</li>
 *   <li>{@code *RequestFulfilled}/{@code RequestCancelled}/{@code ForcedRequestCancelled} — a
 *       request resolved on-chain <em>outside</em> the backend's own submission (the controller
 *       cancelling its own request, or a registry tx sent by another tool) is moved to its
 *       terminal status with the on-chain values, journaled as {@code VAULT_REQUEST_RESOLVED}. A
 *       resolution by a tx the backend itself submitted is left to {@link VaultConfirmationListener}.</li>
 * </ul>
 */
@Service
class VaultRequestIngestionService {

    private static final Logger log = LoggerFactory.getLogger(VaultRequestIngestionService.class);

    /** Blocks per {@code eth_getLogs} call — below the common 10k-block provider limit. */
    static final long MAX_BLOCK_RANGE = 2_000;
    /** Ranges per deployment per run, bounding one tick's backfill work. */
    static final int MAX_RANGES_PER_RUN = 25;

    private final AssetLookupPort assetLookupPort;
    private final AssetDeploymentRepository deploymentRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final EvmContractService evmContractService;
    private final EvmFinalityResolver finalityResolver;
    private final VaultRequestRepository vaultRequestRepository;
    private final VaultRequestIngestCursorRepository cursorRepository;
    private final ChainEffectRecorder chainEffectRecorder;
    private final IsolatedTransactionExecutor isolatedTransactions;

    /** Largest (finalized head − cursor) across ERC-7540 deployments in the most recent run. A
     *  deployment whose run failed contributes (last-known finalized head − persisted cursor). */
    private final AtomicLong maxLagBlocks = new AtomicLong();
    /** Deployments whose ingestion threw in the most recent run (RPC down, eth_getLogs error, …).
     *  The lag gauge alone cannot see an outage: with the head unreadable, the lag freezes at
     *  whatever it last was — 0 for a vault that was caught up. */
    private final AtomicLong failingDeployments = new AtomicLong();
    private final Counter ingestErrors;
    /** Last finalized head successfully read per deployment — the lag basis when a run fails. */
    private final Map<UUID, Long> lastKnownFinalizedHead = new ConcurrentHashMap<>();

    VaultRequestIngestionService(
            AssetLookupPort assetLookupPort,
            AssetDeploymentRepository deploymentRepository,
            ChainConfigRepository chainConfigRepository,
            EvmContractService evmContractService,
            EvmFinalityResolver finalityResolver,
            VaultRequestRepository vaultRequestRepository,
            VaultRequestIngestCursorRepository cursorRepository,
            ChainEffectRecorder chainEffectRecorder,
            IsolatedTransactionExecutor isolatedTransactions,
            MeterRegistry meterRegistry) {
        this.assetLookupPort = assetLookupPort;
        this.deploymentRepository = deploymentRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.evmContractService = evmContractService;
        this.finalityResolver = finalityResolver;
        this.vaultRequestRepository = vaultRequestRepository;
        this.cursorRepository = cursorRepository;
        this.chainEffectRecorder = chainEffectRecorder;
        this.isolatedTransactions = isolatedTransactions;
        Gauge.builder("registerwerk_vault_request_ingest_lag_blocks", maxLagBlocks, AtomicLong::get)
                .description("Largest gap in blocks between an ERC-7540 vault's finalized head and its "
                        + "vault_request ingestion cursor in the most recent run")
                .register(meterRegistry);
        Gauge.builder("registerwerk_vault_request_ingest_failing_deployments", failingDeployments, AtomicLong::get)
                .description("ERC-7540 deployments whose vault_request ingestion failed in the most recent run")
                .register(meterRegistry);
        this.ingestErrors = Counter.builder("registerwerk_vault_request_ingest_errors_total")
                .description("Failed per-deployment ERC-7540 vault_request ingestion runs")
                .register(meterRegistry);
    }

    @SchedulerLock(name = "vaultRequestIngestion", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 50_000)
    public void ingestAll() {
        long maxLag = 0;
        long failing = 0;
        for (AssetLookupPort.AssetInfo asset : assetLookupPort.findAll()) {
            if (asset.tokenStandard() != TokenStandard.ERC7540) {
                continue;
            }
            for (AssetDeployment dep : deploymentRepository.findByAssetId(asset.id())) {
                if (dep.getDeploymentStatus() != AssetDeployment.DeploymentStatus.CONFIRMED
                        || dep.getContractAddress() == null || dep.getChainConfigId() == null) {
                    continue;
                }
                try {
                    maxLag = Math.max(maxLag, ingestDeployment(dep));
                } catch (Exception e) {
                    log.warn("Vault request ingestion failed for deployment={}: {}", dep.getId(), e.getMessage());
                    recordError(dep, e);
                    ingestErrors.increment();
                    failing++;
                    maxLag = Math.max(maxLag, lastKnownLag(dep));
                }
            }
        }
        maxLagBlocks.set(maxLag);
        failingDeployments.set(failing);
    }

    /** Lag of a deployment whose run failed: last finalized head we managed to read minus the
     *  persisted cursor. 0 when neither is known yet (the failing-deployments gauge covers that). */
    private long lastKnownLag(AssetDeployment dep) {
        Long head = lastKnownFinalizedHead.get(dep.getId());
        if (head == null) {
            return 0;
        }
        try {
            long cursor = cursorRepository.findById(dep.getId())
                    .map(VaultRequestIngestCursor::getLastScannedBlock)
                    .orElseGet(() -> startCursor(dep));
            return Math.max(0, head - cursor);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** @return remaining lag in blocks after this run (0 when caught up). */
    long ingestDeployment(AssetDeployment dep) throws IOException {
        ChainConfig chain = chainConfigRepository.findById(dep.getChainConfigId()).orElse(null);
        if (chain == null) {
            return 0;
        }
        Web3j web3j = evmContractService.evmClient(chain.getId());
        Optional<Long> finalizedHead = finalityResolver.finalizedHead(chain, web3j);
        if (finalizedHead.isEmpty()) {
            return 0;
        }
        long cursor = cursorRepository.findById(dep.getId())
                .map(VaultRequestIngestCursor::getLastScannedBlock)
                .orElseGet(() -> startCursor(dep));
        long finalized = finalizedHead.get();
        lastKnownFinalizedHead.put(dep.getId(), finalized);
        for (int i = 0; i < MAX_RANGES_PER_RUN && cursor < finalized; i++) {
            long from = cursor + 1;
            long to = Math.min(finalized, from + MAX_BLOCK_RANGE - 1);
            List<IngestLog> logs = fetch(web3j, dep, from, to);
            isolatedTransactions.run(() -> {
                for (IngestLog entry : logs) {
                    apply(dep, chain, entry);
                }
                VaultRequestIngestCursor c = cursorRepository.findById(dep.getId())
                        .orElseGet(() -> new VaultRequestIngestCursor(dep.getId(), to));
                c.advanceTo(to);
                cursorRepository.save(c);
            });
            cursor = to;
        }
        return Math.max(0, finalized - cursor);
    }

    /** First scan starts at the deployment block — nothing can precede the vault's creation. */
    private static long startCursor(AssetDeployment dep) {
        return dep.getBlockNumber() != null ? Math.max(-1, dep.getBlockNumber() - 1) : -1;
    }

    /** A log plus the RPC-derived context applying it needs — fetched before the write
     *  transaction opens so no RPC round-trip holds a database transaction. */
    record IngestLog(Log log, Instant blockTime, String payer) {}

    private List<IngestLog> fetch(Web3j web3j, AssetDeployment dep, long from, long to) throws IOException {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(to)),
                dep.getContractAddress());
        filter.addOptionalTopics(Erc7540Events.ALL_TOPICS.toArray(String[]::new));
        EthLog response = web3j.ethGetLogs(filter).send();
        if (response.hasError()) {
            // Never advance the cursor over a range we could not read.
            throw new IOException("eth_getLogs failed: " + response.getError().getMessage());
        }
        Map<String, Instant> blockTimes = new HashMap<>();
        List<IngestLog> result = new ArrayList<>();
        for (EthLog.LogResult<?> raw : response.getLogs()) {
            if (!(raw instanceof EthLog.LogObject logObject)) {
                continue;
            }
            Log entry = logObject.get();
            if (Boolean.TRUE.equals(entry.isRemoved()) || entry.getTopics() == null || entry.getTopics().size() < 2) {
                continue;
            }
            String topic = Erc7540Events.topic0(entry);
            Instant blockTime = blockTimes.computeIfAbsent(entry.getBlockHash(), hash -> blockTime(web3j, hash));
            String payer = Erc7540Events.DEPOSIT_REQUESTED_TOPIC.equals(topic)
                    ? readPayer(web3j, dep, Erc7540Events.indexedUint(entry, 1)) : null;
            result.add(new IngestLog(entry, blockTime, payer));
        }
        return result;
    }

    private static Instant blockTime(Web3j web3j, String blockHash) {
        try {
            EthBlock block = web3j.ethGetBlockByHash(blockHash, false).send();
            if (!block.hasError() && block.getBlock() != null && block.getBlock().getTimestamp() != null) {
                return Instant.ofEpochSecond(block.getBlock().getTimestamp().longValueExact());
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Block timestamp unavailable for {}: {}", blockHash, e.getMessage());
        }
        return Instant.now();
    }

    /** {@code depositRequestPayer(id)} — absent on vaults deployed before the payer was recorded
     *  (their cancel refunded the owner), in which case the row keeps a null payer. */
    private String readPayer(Web3j web3j, AssetDeployment dep, BigInteger requestId) {
        try {
            @SuppressWarnings("rawtypes")
            List<Type> out = evmContractService.call(web3j, dep.getContractAddress(), new Function(
                    "depositRequestPayer", List.of(new Uint256(requestId)),
                    List.of(new TypeReference<Address>() {})));
            if (!out.isEmpty()) {
                String payer = out.get(0).getValue().toString().toLowerCase(Locale.ROOT);
                return "0x0000000000000000000000000000000000000000".equals(payer) ? null : payer;
            }
        } catch (RuntimeException e) {
            log.debug("depositRequestPayer({}) unavailable on {}: {}", requestId, dep.getContractAddress(), e.getMessage());
        }
        return null;
    }

    // ── Applying one log ──────────────────────────────────────────────────────

    void apply(AssetDeployment dep, ChainConfig chain, IngestLog entry) {
        Log l = entry.log();
        String topic = Erc7540Events.topic0(l);
        if (Erc7540Events.DEPOSIT_REQUESTED_TOPIC.equals(topic)) {
            ingestRequest(dep, chain, entry, VaultRequestType.DEPOSIT);
        } else if (Erc7540Events.REDEEM_REQUESTED_TOPIC.equals(topic)) {
            ingestRequest(dep, chain, entry, VaultRequestType.REDEEM);
        } else if (Erc7540Events.DEPOSIT_FULFILLED_TOPIC.equals(topic)
                || Erc7540Events.REDEEM_FULFILLED_TOPIC.equals(topic)) {
            BigInteger requestId = Erc7540Events.indexedUint(l, 1);
            Erc7540Events.fulfilment(l, requestId).ifPresent(f -> findRequest(dep, requestId, l).ifPresent(
                    request -> resolve(chain, entry, request, VaultRequestStatus.FULFILLED, f, null, null)));
        } else if (Erc7540Events.REQUEST_CANCELLED_TOPIC.equals(topic)) {
            BigInteger requestId = Erc7540Events.indexedUint(l, 1);
            findRequest(dep, requestId, l).ifPresent(
                    request -> resolve(chain, entry, request, VaultRequestStatus.CANCELLED, null, null, null));
        } else if (Erc7540Events.FORCED_REQUEST_CANCELLED_TOPIC.equals(topic) && l.getTopics().size() >= 3) {
            BigInteger requestId = Erc7540Events.indexedUint(l, 1);
            String to = Erc7540Events.indexedAddress(l, 2);
            String legalBasis = Erc7540Events.data(l, Erc7540Events.FORCED_REQUEST_CANCELLED)
                    .get(0).getValue().toString();
            findRequest(dep, requestId, l).ifPresent(
                    request -> resolve(chain, entry, request, VaultRequestStatus.FORCE_CANCELLED, null, to, legalBasis));
        }
    }

    private void ingestRequest(AssetDeployment dep, ChainConfig chain, IngestLog entry, VaultRequestType type) {
        Log l = entry.log();
        if (l.getTopics().size() < 4) {
            return;
        }
        BigInteger requestId = Erc7540Events.indexedUint(l, 1);
        Optional<VaultRequest> existing = vaultRequestRepository.findByAssetIdAndRequestId(dep.getAssetId(), requestId);
        if (existing.isPresent()) {
            VaultRequest request = existing.get();
            if (request.getRequestedTx() == null) {
                // A row created before ingestion existed (e.g. demo seed) — attach provenance once.
                setProvenance(request, l);
                if (request.getPayerAddr() == null) {
                    request.setPayerAddr(entry.payer());
                }
                vaultRequestRepository.save(request);
            }
            return;
        }
        BigInteger amount = (BigInteger) Erc7540Events.data(l,
                type == VaultRequestType.DEPOSIT ? Erc7540Events.DEPOSIT_REQUESTED : Erc7540Events.REDEEM_REQUESTED)
                .get(0).getValue();
        VaultRequest request = new VaultRequest();
        request.setAssetId(dep.getAssetId());
        request.setRequestId(requestId);
        request.setRequestType(type);
        request.setControllerAddr(Erc7540Events.indexedAddress(l, 2));
        request.setOwnerAddr(Erc7540Events.indexedAddress(l, 3));
        if (type == VaultRequestType.DEPOSIT) {
            request.setAssetAmount(amount);
            request.setPayerAddr(entry.payer());
        } else {
            request.setShareAmount(amount);
        }
        request.setRequestStatus(VaultRequestStatus.PENDING);
        request.setRequestedAt(entry.blockTime());
        setProvenance(request, l);
        VaultRequest saved = vaultRequestRepository.save(request);
        log.info("Ingested ERC-7540 {} request={} for asset={} (block {})",
                type, requestId, dep.getAssetId(), l.getBlockNumber());

        chainEffectRecorder.recordFinalized(descriptor(chain, l,
                VaultRequestIngestRevertCompensator.EFFECT_TYPE, saved));
    }

    private Optional<VaultRequest> findRequest(AssetDeployment dep, BigInteger requestId, Log l) {
        Optional<VaultRequest> request = vaultRequestRepository.findByAssetIdAndRequestId(dep.getAssetId(), requestId);
        if (request.isEmpty()) {
            log.warn("ERC-7540 lifecycle log for unknown request={} on deployment={} (tx={}) — "
                    + "the request event was never ingested; skipping.", requestId, dep.getId(), l.getTransactionHash());
        }
        return request;
    }

    /**
     * Moves {@code request} to {@code target} because the chain says it was resolved by the tx in
     * {@code entry}. No-op when the row already reflects a resolution, or when {@code entry}'s tx
     * is the one the backend itself submitted — {@link VaultConfirmationListener} confirms that.
     */
    private void resolve(ChainConfig chain, IngestLog entry, VaultRequest request, VaultRequestStatus target,
                         Erc7540Events.Fulfilment fulfilment, String forcedTo, String legalBasis) {
        Log l = entry.log();
        String txHash = l.getTransactionHash();
        if (request.isConfirmed() && request.getRequestStatus() != VaultRequestStatus.PENDING) {
            return;
        }
        String ownSubmission = target == VaultRequestStatus.FULFILLED ? request.getFulfilledTx() : request.getCancelledTx();
        if (txHash.equalsIgnoreCase(ownSubmission)) {
            return;
        }
        // Resolved outside the backend's own submission. Any tx the backend had in flight for
        // this request can no longer succeed (the contract requires `pending`); the row is
        // confirmed now, so VaultConfirmationListener stops polling it.
        request.setFulfilledTx(target == VaultRequestStatus.FULFILLED ? txHash : null);
        request.setCancelledTx(target == VaultRequestStatus.FULFILLED ? null : txHash);
        if (fulfilment != null) {
            applyFulfilment(request, fulfilment);
            request.setFulfilledAt(entry.blockTime());
        }
        if (target == VaultRequestStatus.FORCE_CANCELLED) {
            request.setForcedToAddr(forcedTo);
            request.setLegalBasis(legalBasis);
        }
        request.setRequestStatus(target);
        request.setConfirmed(true);
        request.setChainConfigId(chain.getId());
        request.setBlockNumber(l.getBlockNumber().longValueExact());
        request.setBlockHash(l.getBlockHash());
        vaultRequestRepository.save(request);
        log.info("ERC-7540 request={} for asset={} resolved on-chain as {} by tx={} (not submitted by this backend)",
                request.getRequestId(), request.getAssetId(), target, txHash);

        chainEffectRecorder.recordFinalized(descriptor(chain, l,
                VaultRequestFulfillmentRevertCompensator.EFFECT_TYPE, request));
    }

    /** Executed values from a {@code *RequestFulfilled} event — the on-chain truth, never an
     *  operator-typed NAV (T1-08). Shared with {@link VaultConfirmationListener}. */
    static void applyFulfilment(VaultRequest request, Erc7540Events.Fulfilment fulfilment) {
        request.setNavAtFulfill(fulfilment.navPerShare());
        request.setAssetAmount(fulfilment.assets());
        request.setShareAmount(fulfilment.shares());
    }

    private static void setProvenance(VaultRequest request, Log l) {
        request.setRequestedTx(l.getTransactionHash());
        request.setRequestedBlockNumber(l.getBlockNumber().longValueExact());
        request.setRequestedBlockHash(l.getBlockHash());
    }

    private static ChainEffectDescriptor descriptor(ChainConfig chain, Log l, String effectType, VaultRequest request) {
        return new ChainEffectDescriptor(
                chain.getId(), l.getBlockNumber().longValueExact(), l.getBlockHash(), l.getTransactionHash(),
                l.getLogIndex() != null ? l.getLogIndex().intValueExact() : null,
                "blockchain", effectType, "VaultRequest", request.getId(), request.getAssetId(),
                CompensationCategory.INVERSE_FLIP, null, null, null, null);
    }

    private void recordError(AssetDeployment dep, Exception e) {
        try {
            isolatedTransactions.run(() -> cursorRepository.findById(dep.getId()).ifPresent(c -> {
                c.recordError(e.getMessage());
                cursorRepository.save(c);
            }));
        } catch (RuntimeException ignored) {
            // Best effort — the warning above is already logged.
        }
    }
}
