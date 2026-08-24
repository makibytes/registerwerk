package de.makibytes.registerwerk.blockchain.internal.confidential;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialReconciliationCompletedEvent;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.shared.EntityNotFoundException;

/**
 * Compares each holder's decrypted on-chain confidential balance against the register's own
 * plaintext, legally-canonical eWpG §16 balance ({@code AssetHolder.nominalAmount}) — the piece
 * that answers "does the encrypted chain state actually match what Registerwerk's register says
 * it holds?" for confidential (Zama fhEVM) assets, using the headless operator-decrypt path (see
 * {@link ZamaRelayerClient#requestOperatorDecrypt}) since there is no browser/wallet in a
 * scheduled/operator-triggered reconciliation run.
 *
 * <p>Unlike plaintext ERC-20/721/1155 holdings — where {@code HolderDataService} derives
 * {@code nominalAmount} FROM indexed on-chain transfers, so the two can never legitimately
 * disagree — a confidential balance is never indexed in cleartext at all (that's the entire
 * point), so this reconciliation is a genuinely independent check, not a tautology.
 */
@Service
public class ConfidentialBalanceReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialBalanceReconciliationService.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetLookupPort assetLookupPort;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetHolderRepository holderRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final ZamaRelayerClient zamaRelayerClient;
    private final ApplicationEventPublisher eventPublisher;

    // No dedicated table records confidential-reconciliation mismatches (unlike
    // indexer/ChainDriftDetectionJob's chain_drift_event) — the only trace today is a
    // fire-and-forget audit event, so this in-memory "last known state per asset" is what
    // Backs the alerting gauges below.
    private final Map<UUID, Integer> lastMismatchCountByAsset = new ConcurrentHashMap<>();
    private final AtomicLong lastRunEpochSecond = new AtomicLong(0);

    public ConfidentialBalanceReconciliationService(
            AssetLookupPort assetLookupPort,
            AssetDeploymentRepository deploymentRepository,
            AssetHolderRepository holderRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ZamaRelayerClient zamaRelayerClient,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.assetLookupPort = assetLookupPort;
        this.deploymentRepository = deploymentRepository;
        this.holderRepository = holderRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.zamaRelayerClient = zamaRelayerClient;
        this.eventPublisher = eventPublisher;

        Gauge.builder("registerwerk_confidential_reconciliation_mismatch_total", lastMismatchCountByAsset,
                        map -> map.values().stream().mapToInt(Integer::intValue).sum())
                .description("Sum of the most recent mismatch count across all confidential assets' last reconciliation run")
                .register(meterRegistry);
        // Staleness on this gauge is itself a signal: reconcileAll() no-ops silently whenever
        // zamaRelayerClient.isConfigured() is false, so a misconfigured relayer would otherwise
        // never surface as anything other than "reconciliation just never happens."
        Gauge.builder("registerwerk_confidential_reconciliation_last_run_timestamp_seconds", lastRunEpochSecond,
                        AtomicLong::get)
                .description("Unix epoch seconds of the most recent confidential reconciliation run (any asset); 0 if never run")
                .register(meterRegistry);
    }

    /** One holder's register-vs-chain comparison. {@code error} is set instead of throwing when a
     *  single holder's on-chain read/decrypt fails, so one bad handle doesn't abort the whole run. */
    public record HolderReconciliation(
            UUID holderId, String walletAddress, BigDecimal registerAmount,
            BigInteger onchainAmount, boolean matches, String error) {}

    public record ReconciliationReport(
            UUID assetId, String contractAddress, List<HolderReconciliation> holders, boolean allMatch) {}

    /** Scheduled sweep — previously this reconciliation was only reachable
     *  via a manual GET on {@code ConfidentialReconciliationController}, so a register/chain
     *  divergence on a confidential asset could go undetected indefinitely unless an operator
     *  happened to trigger it. Mirrors {@code ConfidentialTravelRuleScreeningService.screenAll()}'s
     *  own scheduling pattern in this same package. Runs after that service's screening pass
     *  (60s fixedDelay there vs. 300s here) so a freshly-decrypted transfer has had a chance to be
     *  reflected before this sweep compares balances. One bad asset's failure is caught and logged
     *  so it cannot abort the sweep for every other confidential asset. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    @SchedulerLock(name = "confidentialBalanceReconciliation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT20S")
    public void reconcileAll() {
        if (!zamaRelayerClient.isConfigured()) {
            return;
        }
        for (AssetLookupPort.AssetInfo asset : assetLookupPort.findAll()) {
            if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
                continue;
            }
            try {
                reconcile(asset.id(), SYSTEM_ACTOR, "SYSTEM");
            } catch (Exception e) {
                log.warn("Scheduled confidential reconciliation failed for asset={}: {}", asset.id(), e.getMessage());
            }
        }
    }

    /** Manual/on-demand entry point (e.g. the REST controller) — attributes the run to the
     *  system actor, since there is no authenticated caller in this overload. */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(UUID assetId) {
        return reconcile(assetId, SYSTEM_ACTOR, "SYSTEM");
    }

    /**
     * @param actorId   who triggered this run — a full reconciliation
     *                  decrypts every holder's confidential balance via the registry's
     *                  operator-viewer key, which is itself a sensitive action; previously neither
     *                  the actor nor the fact that a clean run even happened was recorded anywhere.
     * @param actorRole the triggering actor's role, for the audit record
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(UUID assetId, UUID actorId, String actorRole) {
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException(
                    "Confidential balance reconciliation is only available for confidential token standards");
        }
        if (!zamaRelayerClient.isConfigured()) {
            throw new IllegalStateException(
                    "Confidential balance reconciliation requires a configured Zama relayer sidecar "
                    + "(registerwerk.zama.relayer-url) to decrypt on-chain balances.");
        }

        AssetDeployment dep = deploymentRepository.findByAssetId(assetId).stream()
                .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                        && d.getContractAddress() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No confirmed on-chain deployment found for asset=" + assetId));

        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);

        // Deliberately unfiltered (includes soft-deleted/removed rows): this is an independent
        // audit check of on-chain vs. off-chain truth, and a removed holder whose on-chain balance
        // was never actually reduced to zero is exactly the kind of mismatch this must catch.
        List<AssetHolder> holders = holderRepository.findByAssetId(assetId);
        List<HolderReconciliation> results = new ArrayList<>(holders.size());
        List<HolderReconciliation> mismatches = new ArrayList<>();

        for (AssetHolder holder : holders) {
            HolderReconciliation r = reconcileOne(dep, web3j, holder);
            results.add(r);
            if (!r.matches()) {
                mismatches.add(r);
            }
        }

        // Always published, not just on mismatch — a clean run still
        // decrypted every holder's balance via the operator-viewer key, so "who ran reconciliation
        // and when, and did it pass" must be answerable from the audit trail even when there was
        // nothing wrong to report; mismatchCount=0 on a clean run is the "it ran and passed" record.
        eventPublisher.publishEvent(new ConfidentialReconciliationCompletedEvent(
                assetId, dep.getId(), actorId, actorRole, holders.size(), mismatches.size(), summarize(mismatches)));
        if (!mismatches.isEmpty()) {
            log.warn("Confidential reconciliation: {} of {} holder(s) mismatched for asset={}",
                    mismatches.size(), holders.size(), assetId);
        }
        lastMismatchCountByAsset.put(assetId, mismatches.size());
        lastRunEpochSecond.set(Instant.now().getEpochSecond());

        boolean allMatch = mismatches.isEmpty();
        return new ReconciliationReport(assetId, dep.getContractAddress(), results, allMatch);
    }

    private HolderReconciliation reconcileOne(AssetDeployment dep, Web3j web3j, AssetHolder holder) {
        try {
            Function balanceOf = new Function("confidentialBalanceOf",
                    Collections.singletonList(new Address(holder.getWalletAddress())),
                    Collections.singletonList(new TypeReference<Uint256>() {}));
            List<Type> raw = evmContractService.call(web3j, dep.getContractAddress(), balanceOf);
            BigInteger handleValue = ((Uint256) raw.get(0)).getValue();
            String handleHex = EvmUtils.uint256ToBytes32Hex(handleValue);

            BigInteger onchainAmount = zamaRelayerClient.requestOperatorDecrypt(handleHex, dep.getContractAddress());
            // onchainAmount is raw base units (ConfidentialERC20.decimals() = 6); nominalAmount is
            // already human-readable/scaled, exactly like ConfidentialTravelRuleScreeningService's
            // own decrypt-and-compare path — without this scaling, every
            // holder with a nonzero balance would be reported as a mismatch.
            boolean matches = onchainAmount != null
                    && new BigDecimal(onchainAmount)
                            .movePointLeft(ConfidentialTravelRuleScreeningService.MAX_HANDLE_DECIMALS)
                            .compareTo(holder.getNominalAmount()) == 0;
            return new HolderReconciliation(
                    holder.getId(), holder.getWalletAddress(), holder.getNominalAmount(), onchainAmount, matches, null);
        } catch (Exception e) {
            log.warn("Confidential reconciliation failed for holder={} asset={}: {}",
                    holder.getId(), holder.getAssetId(), e.getMessage());
            return new HolderReconciliation(
                    holder.getId(), holder.getWalletAddress(), holder.getNominalAmount(), null, false, e.getMessage());
        }
    }

    private static Map<String, Object> summarize(List<HolderReconciliation> mismatches) {
        List<Map<String, Object>> rows = mismatches.stream()
                .map(m -> Map.<String, Object>of(
                        "holderId", m.holderId(),
                        "walletAddress", m.walletAddress(),
                        "registerAmount", m.registerAmount(),
                        "onchainAmount", m.onchainAmount() != null ? m.onchainAmount() : "unavailable: " + m.error()))
                .toList();
        return Map.of("mismatches", rows);
    }
}
