package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.MintControlRule;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.MintControlRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled job that synchronises on-chain {@code MintAllowanceSet} events with the DB.
 *
 * <p>Every 5 minutes:
 * <ol>
 *   <li>Iterates all CONFIRMED EVM deployments</li>
 *   <li>Queries {@code eth_getLogs} for {@code MintAllowanceSet(address indexed target, uint256 amount)}
 *       events since the last scanned block</li>
 *   <li>Upserts {@link MintControlRule} records with the on-chain allowance</li>
 * </ol>
 *
 * <p>The last-scanned block is kept in memory (reset to 0 on restart). A persistent
 * cursor (e.g. stored in a {@code chain_sync_cursor} table) can be added later for
 * high-reliability deployments.
 */
@Component
public class MintControlSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MintControlSyncJob.class);

    /**
     * {@code MintAllowanceSet(address indexed target, uint256 amount)} — topic0.
     */
    private static final Event MINT_ALLOWANCE_SET_EVENT = new Event(
            "MintAllowanceSet",
            Arrays.asList(
                    new TypeReference<Address>(true) {},   // indexed: target
                    new TypeReference<Uint256>(false) {}   // non-indexed: amount
            )
    );

    private static final String MINT_ALLOWANCE_SET_TOPIC = EventEncoder.encode(MINT_ALLOWANCE_SET_EVENT);

    /** In-memory per-chain last scanned block (resets on restart). */
    private final Map<String, BigInteger> lastScannedBlock = new ConcurrentHashMap<>();

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final MintControlRuleRepository mintControlRuleRepository;

    public MintControlSyncJob(
            BlockchainClientRegistry blockchainClientRegistry,
            AssetDeploymentRepository assetDeploymentRepository,
            MintControlRuleRepository mintControlRuleRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.mintControlRuleRepository = mintControlRuleRepository;
    }

    /**
     * Runs every 5 minutes to pull on-chain {@code MintAllowanceSet} events and reconcile
     * them with {@link MintControlRule} records in the database.
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void syncFromChain() {
        log.info("MintControlSyncJob: starting scan");

        for (Map.Entry<ChainDescriptor, Web3j> entry : blockchainClientRegistry.getEvmClients().entrySet()) {
            ChainDescriptor descriptor = entry.getKey();
            if (descriptor.chain() == Chain.SOLANA) continue; // Solana handled separately

            Web3j web3j = entry.getValue();
            String chainKey = descriptor.chain().name() + "_" + descriptor.network().name();

            try {
                scanChain(web3j, chainKey, descriptor);
            } catch (Exception e) {
                log.error("MintControlSyncJob: error scanning chain={}: {}", chainKey, e.getMessage(), e);
            }
        }

        log.info("MintControlSyncJob: scan complete");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void scanChain(Web3j web3j, String chainKey, ChainDescriptor descriptor) throws Exception {
        BigInteger fromBlock = lastScannedBlock.getOrDefault(chainKey, BigInteger.ZERO);

        // Collect contract addresses for all CONFIRMED deployments on this chain
        List<AssetDeployment> confirmed = assetDeploymentRepository
                .findByChainAndNetwork(descriptor.chain(), descriptor.network())
                .stream()
                .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                        && d.getContractAddress() != null
                        && !d.getContractAddress().startsWith("0x-PENDING"))
                .toList();

        if (confirmed.isEmpty()) {
            log.debug("MintControlSyncJob: no confirmed deployments for chain={}", chainKey);
            return;
        }

        BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
        if (latestBlock.compareTo(fromBlock) <= 0) {
            log.debug("MintControlSyncJob: chain={} already up to date at block={}", chainKey, latestBlock);
            return;
        }

        for (AssetDeployment deployment : confirmed) {
            scanDeployment(web3j, deployment, fromBlock, latestBlock);
        }

        lastScannedBlock.put(chainKey, latestBlock);
        log.info("MintControlSyncJob: chain={} scanned blocks {}-{}", chainKey, fromBlock, latestBlock);
    }

    private void scanDeployment(Web3j web3j, AssetDeployment deployment,
                                 BigInteger fromBlock, BigInteger toBlock) throws Exception {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(fromBlock),
                DefaultBlockParameter.valueOf(toBlock),
                deployment.getContractAddress()
        ).addSingleTopic(MINT_ALLOWANCE_SET_TOPIC);

        EthLog ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) {
            log.warn("MintControlSyncJob: ethGetLogs error for deployment={}: {}",
                    deployment.getId(), ethLog.getError().getMessage());
            return;
        }

        for (EthLog.LogResult<?> logResult : ethLog.getLogs()) {
            if (!(logResult instanceof EthLog.LogObject)) continue;
            Log logEntry = ((EthLog.LogObject) logResult).get();

            if (logEntry.getTopics().size() < 2) continue;

            // topics[1] = indexed address (target), padded to 32 bytes
            String paddedTarget = logEntry.getTopics().get(1);
            String targetAddress = "0x" + paddedTarget.substring(paddedTarget.length() - 40);

            // data = uint256 amount (non-indexed)
            BigInteger amount = Numeric.decodeQuantity(logEntry.getData());

            upsertMintAllowance(deployment.getId(), targetAddress, amount);
        }
    }

    private void upsertMintAllowance(java.util.UUID deploymentId, String targetAddress,
                                      BigInteger amount) {
        mintControlRuleRepository
                .findByAssetDeploymentIdAndTargetAddress(deploymentId, targetAddress)
                .ifPresentOrElse(
                        rule -> {
                            rule.setMaxAmount(new java.math.BigDecimal(amount));
                            rule.setActive(true);
                            mintControlRuleRepository.save(rule);
                            log.debug("MintControlSyncJob: updated allowance for target={} amount={}",
                                    targetAddress, amount);
                        },
                        () -> {
                            MintControlRule rule = new MintControlRule();
                            rule.setAssetDeploymentId(deploymentId);
                            rule.setTargetAddress(targetAddress);
                            rule.setRuleType(MintControlRule.RuleType.MINT_ALLOWANCE);
                            rule.setMaxAmount(new java.math.BigDecimal(amount));
                            rule.setActive(true);
                            mintControlRuleRepository.save(rule);
                            log.debug("MintControlSyncJob: created allowance for target={} amount={}",
                                    targetAddress, amount);
                        }
                );
    }
}
