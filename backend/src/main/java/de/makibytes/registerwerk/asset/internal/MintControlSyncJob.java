package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.MintControlRule;
import de.makibytes.registerwerk.deployment.api.MintControlRuleRepository;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
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
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syncs MintAllowanceSet events from chain. Moved from blockchain/api to asset/api
 * to break the asset ↔ blockchain modulith cycle (Track F).
 */
@Component
public class MintControlSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MintControlSyncJob.class);

    private static final Event MINT_ALLOWANCE_SET_EVENT = new Event(
            "MintAllowanceSet",
            Arrays.asList(
                    new TypeReference<Address>(true) {},
                    new TypeReference<Uint256>(false) {}
            )
    );
    private static final String MINT_ALLOWANCE_SET_TOPIC = EventEncoder.encode(MINT_ALLOWANCE_SET_EVENT);
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

    @Scheduled(cron = "0 */5 * * * *")
    public void syncFromChain() {
        log.info("MintControlSyncJob: starting scan");
        for (Map.Entry<ChainDescriptor, Web3j> entry : blockchainClientRegistry.getEvmClients().entrySet()) {
            ChainDescriptor descriptor = entry.getKey();
            if (descriptor.chain() == Chain.SOLANA) continue;
            String chainKey = descriptor.chain().name() + "_" + descriptor.network().name();
            try {
                scanChain(entry.getValue(), chainKey, descriptor);
            } catch (Exception e) {
                log.error("MintControlSyncJob: error chain={}: {}", chainKey, e.getMessage(), e);
            }
        }
        log.info("MintControlSyncJob: scan complete");
    }

    private void scanChain(Web3j web3j, String chainKey, ChainDescriptor descriptor) throws Exception {
        BigInteger fromBlock = lastScannedBlock.getOrDefault(chainKey, BigInteger.ZERO);
        List<AssetDeployment> confirmed = assetDeploymentRepository
                .findByChainAndNetwork(descriptor.chain(), descriptor.network())
                .stream()
                .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                        && d.getContractAddress() != null)
                .toList();
        if (confirmed.isEmpty()) return;
        BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
        if (latestBlock.compareTo(fromBlock) <= 0) return;
        for (AssetDeployment deployment : confirmed) {
            scanDeployment(web3j, deployment, fromBlock, latestBlock);
        }
        lastScannedBlock.put(chainKey, latestBlock);
    }

    private void scanDeployment(Web3j web3j, AssetDeployment deployment,
                                 BigInteger fromBlock, BigInteger toBlock) throws Exception {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(fromBlock),
                DefaultBlockParameter.valueOf(toBlock),
                deployment.getContractAddress()
        ).addSingleTopic(MINT_ALLOWANCE_SET_TOPIC);
        EthLog ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) return;
        for (EthLog.LogResult<?> logResult : ethLog.getLogs()) {
            if (!(logResult instanceof EthLog.LogObject)) continue;
            Log logEntry = ((EthLog.LogObject) logResult).get();
            if (logEntry.getTopics().size() < 2) continue;
            String paddedTarget = logEntry.getTopics().get(1);
            String targetAddress = "0x" + paddedTarget.substring(paddedTarget.length() - 40);
            BigInteger amount = Numeric.decodeQuantity(logEntry.getData());
            upsertMintAllowance(deployment.getId(), targetAddress, amount);
        }
    }

    private void upsertMintAllowance(UUID deploymentId, String targetAddress, BigInteger amount) {
        mintControlRuleRepository
                .findByAssetDeploymentIdAndTargetAddress(deploymentId, targetAddress)
                .ifPresentOrElse(
                        rule -> { rule.setMaxAmount(new BigDecimal(amount)); rule.setActive(true); mintControlRuleRepository.save(rule); },
                        () -> {
                            MintControlRule rule = new MintControlRule();
                            rule.setAssetDeploymentId(deploymentId);
                            rule.setTargetAddress(targetAddress);
                            rule.setRuleType(MintControlRule.RuleType.MINT_ALLOWANCE);
                            rule.setMaxAmount(new BigDecimal(amount));
                            rule.setActive(true);
                            mintControlRuleRepository.save(rule);
                        }
                );
    }
}
