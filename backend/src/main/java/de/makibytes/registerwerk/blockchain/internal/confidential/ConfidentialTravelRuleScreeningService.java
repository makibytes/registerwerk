package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialTransferScreenedEvent;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialTokenEventReader.ConfidentialTokenEvent;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled service that detects, decrypts and screens confidential (Zama fhEVM) transfers for
 * FATF/TFR Travel Rule obligations.
 *
 * <p>A holder-initiated {@code confidentialTransfer} is signed and submitted entirely
 * client-side; the backend never sees it in flight the way it sees a {@code
 * TokenAdminService.forcedTransfer}. This service closes that gap after the fact: it polls the
 * indexed {@code ConfidentialTransfer}/{@code ConfidentialMint} event log (cleartext {@code
 * from}/{@code to}, encrypted {@code handle} — see {@code
 * indexer/evm/subgraph/src/confidentialToken.ts}) for every confirmed confidential deployment,
 * decrypts each new handle via the registry's already-provisioned operator-viewer key ({@link
 * ZamaRelayerClient#requestOperatorDecrypt} — the same capability {@code
 * ConfidentialBalanceReconciliationService} already uses, not a new privilege), and evaluates the
 * transfer through the same {@link TravelRuleGate} port {@code TokenAdminService.forcedTransfer}
 * uses for backend-initiated transfers.
 *
 * <p>This is necessarily post-hoc, not preventive: the on-chain transfer has already settled by
 * the time it is observed, decrypted and screened. There is no FX/price-oracle infrastructure in
 * this codebase to convert an arbitrary confidential security's unit count into a EUR-equivalent
 * value, so {@code amountEur} is passed as {@code null} to {@link TravelRuleGate} — exactly the
 * same convention {@code TokenAdminService.forcedTransfer} already uses — while the real decrypted
 * amount is still recorded in its own native denomination via the gate's finding-#8 overload,
 * closing the "no durable, queryable amount in the audit trail" gap for confidential transfers.
 */
@Service
public class ConfidentialTravelRuleScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialTravelRuleScreeningService.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    static final int MAX_HANDLE_DECIMALS = 6; // matches ConfidentialERC20.decimals (see contract)

    /** Consecutive scheduled runs a decrypt failure is retried before the cursor gives up and
     *  advances past it anyway — bounds how long Travel Rule screening
     *  can be silently blocked by a single stuck event without risking a permanent wedge if the
     *  failure turns out to be non-transient (e.g. a missing ACL grant) rather than a passing
     *  relayer/KMS hiccup. At the 60s fixedDelay above, 5 attempts is ~5 minutes of retrying. */
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private final AssetLookupPort assetLookupPort;
    private final AssetDeploymentRepository deploymentRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final ConfidentialTransferScreeningStateRepository stateRepository;
    private final ConfidentialTokenEventReader eventReader;
    private final ZamaRelayerClient zamaRelayerClient;
    private final TravelRuleGate travelRuleGate;
    private final ApplicationEventPublisher eventPublisher;

    ConfidentialTravelRuleScreeningService(
            AssetLookupPort assetLookupPort,
            AssetDeploymentRepository deploymentRepository,
            ChainConfigRepository chainConfigRepository,
            ConfidentialTransferScreeningStateRepository stateRepository,
            ConfidentialTokenEventReader eventReader,
            ZamaRelayerClient zamaRelayerClient,
            TravelRuleGate travelRuleGate,
            ApplicationEventPublisher eventPublisher) {
        this.assetLookupPort = assetLookupPort;
        this.deploymentRepository = deploymentRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.stateRepository = stateRepository;
        this.eventReader = eventReader;
        this.zamaRelayerClient = zamaRelayerClient;
        this.travelRuleGate = travelRuleGate;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "confidentialTravelRuleScreening", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
    public void screenAll() {
        if (!zamaRelayerClient.isConfigured()) {
            return;
        }
        for (AssetLookupPort.AssetInfo asset : assetLookupPort.findAll()) {
            if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
                continue;
            }
            deploymentRepository.findByAssetId(asset.id()).stream()
                    .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                            && d.getContractAddress() != null)
                    .forEach(dep -> screenDeployment(asset, dep));
        }
    }

    /** One deployment's poll-decrypt-screen pass. Isolated per deployment so one bad/unreachable
     *  chain or a single failed decrypt cannot abort screening for every other confidential asset. */
    @Transactional
    void screenDeployment(AssetLookupPort.AssetInfo asset, AssetDeployment dep) {
        String identifier = dep.getChain().name() + "_" + dep.getNetwork().name();
        ChainConfig chainConfig = chainConfigRepository.findByIdentifier(identifier).orElse(null);
        if (chainConfig == null) {
            return;
        }

        ConfidentialTransferScreeningState state = stateRepository.findByAssetDeploymentId(dep.getId())
                .orElseGet(() -> {
                    ConfidentialTransferScreeningState s = new ConfidentialTransferScreeningState();
                    s.setAssetDeploymentId(dep.getId());
                    return s;
                });

        List<ConfidentialTokenEvent> events;
        try {
            events = eventReader.eventsSince(chainConfig, dep.getContractAddress(), state.getLastScreenedBlock());
        } catch (Exception e) {
            log.warn("Confidential transfer screening: failed to query events for asset={} deployment={}: {}",
                    asset.id(), dep.getId(), e.getMessage());
            state.setLastError(e.getMessage());
            stateRepository.save(state);
            return;
        }
        if (events.isEmpty()) {
            return;
        }

        // Only credit the cursor with blocks up to (not including) the first event whose decrypt
        // failed in this batch — a failed event is retried on the next scheduled run (still
        // included in eventsSince, since the cursor doesn't move past it) rather than being
        // silently skipped. See MAX_RETRY_ATTEMPTS for the bound.
        long resolvedThrough = state.getLastScreenedBlock();
        boolean allResolved = true;
        for (ConfidentialTokenEvent event : events) {
            boolean succeeded = screenOne(asset, dep, event);
            if (succeeded && allResolved) {
                resolvedThrough = Math.max(resolvedThrough, event.blockNumber());
            } else {
                allResolved = false;
            }
        }

        if (allResolved) {
            state.setLastScreenedBlock(resolvedThrough);
            state.setConsecutiveDecryptFailures(0);
        } else {
            int attempts = state.getConsecutiveDecryptFailures() + 1;
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                long highestInBatch = events.stream().mapToLong(ConfidentialTokenEvent::blockNumber).max()
                        .orElse(resolvedThrough);
                log.error("Confidential transfer screening: giving up after {} attempts for asset={} "
                                + "deployment={} — advancing past unresolved event(s) to avoid permanently "
                                + "blocking newer transfers. Manual investigation required (check "
                                + "ConfidentialTransferScreenedEvent audit records with decryptSucceeded=false).",
                        attempts, asset.id(), dep.getId());
                state.setLastScreenedBlock(highestInBatch);
                state.setConsecutiveDecryptFailures(0);
            } else {
                state.setLastScreenedBlock(resolvedThrough);
                state.setConsecutiveDecryptFailures(attempts);
            }
        }
        state.setLastRunAt(java.time.Instant.now());
        state.setLastError(null);
        stateRepository.save(state);
    }

    /** @return true if the event's ciphertext handle was successfully decrypted (regardless of
     *  what Travel Rule evaluation subsequently decided) — used by {@link #screenDeployment} to
     *  decide how far the sync cursor may safely advance. */
    private boolean screenOne(AssetLookupPort.AssetInfo asset, AssetDeployment dep, ConfidentialTokenEvent event) {
        BigInteger decrypted = null;
        String error = null;
        try {
            String handleHex = EvmUtils.uint256ToBytes32Hex(event.handle());
            decrypted = zamaRelayerClient.requestOperatorDecrypt(handleHex, dep.getContractAddress());
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("Confidential transfer screening: decrypt failed for asset={} tx={} logIndex={}: {}",
                    asset.id(), event.transactionHash(), event.logIndex(), error);
        }

        // MINT has no originator wallet to screen (issuance, not a transfer between holders);
        // Travel Rule concerns who receives funds from whom, so only evaluate real transfers.
        if ("TRANSFER".equals(event.eventType()) && decrypted != null) {
            BigDecimal nativeAmount = new BigDecimal(decrypted).movePointLeft(MAX_HANDLE_DECIMALS);
            try {
                travelRuleGate.enforceOutbound(asset.id(), event.from(), event.to(), null,
                        nativeAmount, EvmUtils.tokenSymbol(asset.name()));
            } catch (Exception travelRuleFailure) {
                // enforceOutbound throws when it must not proceed (e.g. VASP beneficiary with no
                // protocol adapter configured) — the transfer already settled on-chain and cannot
                // be blocked retroactively; the ComplianceGateException itself already persisted
                // a FAILED travel_rule_message row, so this is a log, not a re-throw.
                log.warn("Confidential transfer screening: Travel Rule evaluation failed for asset={} tx={}: {}",
                        asset.id(), event.transactionHash(), travelRuleFailure.getMessage());
            }
        }

        eventPublisher.publishEvent(new ConfidentialTransferScreenedEvent(
                asset.id(), dep.getId(), SYSTEM_ACTOR, "SYSTEM",
                event.eventType(), event.from(), event.to(),
                event.transactionHash(), event.logIndex(), decrypted != null, error));

        return decrypted != null;
    }
}
