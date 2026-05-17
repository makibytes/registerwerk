package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetHolder;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.asset.web.dto.LiveHolderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scans on-chain Transfer events to build live holder balances.
 *
 * <p>For a given token deployment, fetches all Transfer events from the blockchain,
 * computes the current balance of each address, and enriches with Registerwerk identity data
 * (investor name, KYC status, whitelist status).
 *
 * <p>Unknown holders: any address with a non-zero balance not linked to an AssetHolder record.
 */
@Service
@Transactional(readOnly = true)
public class LiveHolderService {

    private static final Logger log = LoggerFactory.getLogger(LiveHolderService.class);

    /** ERC-20 Transfer(address indexed from, address indexed to, uint256 value) event topic. */
    private static final String TRANSFER_EVENT_TOPIC = EventEncoder.encode(
            new Event("Transfer",
                    Arrays.asList(
                            new TypeReference<Address>(true) {},
                            new TypeReference<Address>(true) {},
                            new TypeReference<Uint256>() {}
                    ))
    );

    private final AssetDeploymentRepository deploymentRepository;
    private final HolderService holderService;
    private final LegalEntityRepository legalEntityRepository;
    private final BlockchainClientRegistry clientRegistry;

    public LiveHolderService(
            AssetDeploymentRepository deploymentRepository,
            HolderService holderService,
            LegalEntityRepository legalEntityRepository,
            BlockchainClientRegistry clientRegistry) {
        this.deploymentRepository = deploymentRepository;
        this.holderService = holderService;
        this.legalEntityRepository = legalEntityRepository;
        this.clientRegistry = clientRegistry;
    }

    /**
     * Refreshes live holder balances by scanning on-chain Transfer events.
     *
     * @param deploymentId ID of the asset deployment to scan
     * @return list of live holders with balance, identity, and whitelist status
     * @throws EntityNotFoundException if deployment not found
     * @throws RuntimeException if blockchain call fails
     */
    public List<LiveHolderResponse> refreshLiveHolders(UUID deploymentId) {
        log.info("Refreshing live holders for deployment={}", deploymentId);

        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));

        if (dep.getChain() == Chain.SOLANA) {
            throw new UnsupportedOperationException(
                    "Live holder scanning from Transfer events is only supported for EVM deployments");
        }

        if (dep.getContractAddress() == null || dep.getContractAddress().startsWith("0x-PENDING")) {
            log.warn("Token contract not deployed for deployment={}", deploymentId);
            return Collections.emptyList();
        }

        // Build live balance map from Transfer events
        Map<String, BigDecimal> balanceMap = scanTransferEvents(dep);

        // Enrich with Registerwerk data
        List<LiveHolderResponse> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : balanceMap.entrySet()) {
            String walletAddress = entry.getKey();
            BigDecimal balance = entry.getValue();

            // Look up holder record in DB
            Optional<AssetHolder> holder = holderService.findByAssetIdAndWalletAddress(dep.getAssetId(), walletAddress);

            UUID entityId = null;
            String entityName = null;
            boolean known = holder.isPresent();
            boolean whitelisted = holder.map(AssetHolder::getWhitelisted).orElse(false);

            if (holder.isPresent()) {
                entityId = holder.get().getInvestorId();
                Optional<LegalEntity> entity = legalEntityRepository.findById(entityId);
                if (entity.isPresent()) {
                    entityName = entity.get().getCurrentName();
                }
            }

            result.add(new LiveHolderResponse(
                    walletAddress,
                    balance,
                    entityId,
                    entityName,
                    known,
                    whitelisted
            ));

            log.debug("Live holder: address={}, balance={}, name={}, known={}, whitelisted={}",
                    walletAddress, balance, entityName, known, whitelisted);
        }

        log.info("Refreshed {} live holders for deployment={}", result.size(), deploymentId);
        return result;
    }

    /**
     * Scans ethGetLogs for Transfer events and computes final balances.
     *
     * <p>This is a simplified balance calculation: for each Transfer event, we update
     * the balance map (from -= amount, to += amount). For production at scale, consider
     * calling balanceOf on-chain to confirm.
     */
    private Map<String, BigDecimal> scanTransferEvents(AssetDeployment dep) {
        Map<String, BigDecimal> balances = new HashMap<>();

        try {
            Web3j evmClient = clientRegistry.getEvmClient(new ChainDescriptor(dep.getChain(), dep.getNetwork()));

            // Note: In a production system, you may want to paginate blocks or use a subgraph.
            // For now, we scan from genesis (block 0) to latest. This is simple but may be slow on large chains.
            EthFilter filter = new EthFilter(
                    DefaultBlockParameterName.EARLIEST,
                    DefaultBlockParameterName.LATEST,
                    dep.getContractAddress()
            );
            filter.addSingleTopic(TRANSFER_EVENT_TOPIC);

            List<Log> logs = evmClient.ethGetLogs(filter).send().getLogs().stream()
                    .filter(r -> r instanceof Log).map(r -> (Log) r).toList();

            for (Log log : logs) {
                // topic[0] = Transfer, topic[1] = from, topic[2] = to
                if (log.getTopics().size() < 3) continue;

                String from = "0x" + log.getTopics().get(1).substring(26); // Remove leading zeros
                String to = "0x" + log.getTopics().get(2).substring(26);
                BigInteger value = new BigInteger(Numeric.cleanHexPrefix(log.getData()), 16);

                // Update balances
                balances.put(from, balances.getOrDefault(from, BigDecimal.ZERO)
                        .subtract(new BigDecimal(value)));
                balances.put(to, balances.getOrDefault(to, BigDecimal.ZERO)
                        .add(new BigDecimal(value)));
            }

            // Filter out zero balances
            balances.entrySet().removeIf(e -> e.getValue().signum() == 0);

        } catch (Exception e) {
            log.error("Error scanning Transfer events for deployment={}", dep.getId(), e);
            throw new RuntimeException("Failed to scan blockchain for Transfer events: " + e.getMessage(), e);
        }

        return balances;
    }
}
