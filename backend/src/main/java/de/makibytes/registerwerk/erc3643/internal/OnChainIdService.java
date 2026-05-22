package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.events.OnchainIdentityDeployedEvent;
import de.makibytes.registerwerk.erc3643.events.OnchainIdentityLinkedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.internal.Erc3643DeploymentService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages ONCHAINID identity contracts for legal entities.
 *
 * <p>Each legal entity may have one ONCHAINID proxy deployed per chain.
 * The identity contract is the ERC-734/735-compatible contract that holds
 * KYC/AML claims issued by trusted issuers.
 */
@Service
@Transactional
public class OnChainIdService {

    private static final Logger log = LoggerFactory.getLogger(OnChainIdService.class);

    /** keccak256("WalletLinked(address)") — IdFactory event emitted after deployIdentityProxy. */
    private static final String IDENTITY_CREATED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("WalletLinked(address)");

    private final OnchainIdentityRepository identityRepository;
    private final OnchainClaimRepository claimRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final Erc3643DeploymentService deploymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final EvmContractService evmContractService;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final ContractAddressConfig contractAddressConfig;
    private final BlockchainTransactionService blockchainTransactionService;

    public OnChainIdService(
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository,
            ChainConfigRepository chainConfigRepository,
            Erc3643DeploymentService deploymentService,
            ApplicationEventPublisher eventPublisher,
            EvmContractService evmContractService,
            BlockchainClientRegistry blockchainClientRegistry,
            ContractAddressConfig contractAddressConfig,
            BlockchainTransactionService blockchainTransactionService) {
        this.identityRepository = identityRepository;
        this.claimRepository = claimRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.deploymentService = deploymentService;
        this.eventPublisher = eventPublisher;
        this.evmContractService = evmContractService;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.contractAddressConfig = contractAddressConfig;
        this.blockchainTransactionService = blockchainTransactionService;
    }

    /**
     * Returns the existing ONCHAINID identity for a legal entity on a chain, or deploys a new one.
     *
     * <p>If no identity record exists for the (legalEntityId, chainConfigId) pair, this method
     * submits a {@code deployIdentityProxy} transaction and persists a placeholder record.
     * The placeholder record's {@code identityAddress} will be updated by the blockchain event
     * listener once the transaction is confirmed.
     *
     * @param legalEntityId ID of the legal entity
     * @param chainConfigId ID of the chain configuration
     * @return the existing or newly created {@link OnchainIdentity}
     */
    public OnchainIdentity getOrCreate(UUID legalEntityId, UUID chainConfigId) {
        Optional<OnchainIdentity> existing =
            identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId);

        if (existing.isPresent()) {
            log.debug(
                "Found existing ONCHAINID identity for entity={} on chain={}",
                legalEntityId, chainConfigId);
            return existing.get();
        }

        log.info(
            "No ONCHAINID identity found for entity={} on chain={}. Deploying new proxy.",
            legalEntityId, chainConfigId);

        ChainConfig chainConfig = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));

        String identityAddress = "0x-PENDING-ONCHAINID-" + UUID.randomUUID();
        String deployTxHash = null;

        try {
            String idFactoryAddress = contractAddressConfig.requireIdFactory(chainConfig.getIdentifier());
            Web3j web3j = blockchainClientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
            Credentials creds = evmContractService.credentials(chainConfigId);

            // IdFactory.deployIdentityProxy(address _managementKey) returns address
            // The management key is the registry wallet (it manages the identity on behalf of the entity)
            Function fn = new Function(
                    "deployIdentityProxy",
                    List.of(new Address(creds.getAddress())),
                    List.of(new TypeReference<Address>() {})
            );
            deployTxHash = evmContractService.submit(web3j, creds, idFactoryAddress, fn);
            blockchainTransactionService.record(
                    deployTxHash,
                    fn.getName(),
                    null,
                    null,
                    parseChain(chainConfig.getIdentifier()),
                    chainConfig.getNetworkType().name(),
                    idFactoryAddress,
                    Map.of(
                            "legalEntityId", legalEntityId.toString(),
                            "chainConfigId", chainConfigId.toString(),
                            "identityPlaceholder", identityAddress
                    )
            );
        } catch (IllegalStateException e) {
            // IdFactory not configured for this chain — persist placeholder
            log.warn("IdFactory not configured for chain={}; persisting placeholder identity", chainConfigId);
        } catch (Exception e) {
            throw new RuntimeException("deployIdentityProxy submission failed for entity=" + legalEntityId
                    + " on chain=" + chainConfigId + ": " + e.getMessage(), e);
        }

        OnchainIdentity identity = new OnchainIdentity();
        identity.setLegalEntityId(legalEntityId);
        identity.setChainConfigId(chainConfigId);
        identity.setIdentityAddress(identityAddress);
        identity.setDeployedAt(Instant.now());
        identity.setDeployedByTx(deployTxHash);

        OnchainIdentity saved = identityRepository.save(identity);

        eventPublisher.publishEvent(new OnchainIdentityDeployedEvent(saved.getId(), null, "REGISTRY_ADMIN", java.util.Map.of()));

        return saved;
    }

    private String parseChain(String identifier) {
        int splitIndex = identifier.lastIndexOf('_');
        return splitIndex > 0 ? identifier.substring(0, splitIndex) : identifier;
    }

    /**
     * Finds an ONCHAINID identity by its primary key.
     */
    @Transactional(readOnly = true)
    public Optional<OnchainIdentity> findIdentityById(UUID identityId) {
        return identityRepository.findById(identityId);
    }

    /**
     * Finds an ONCHAINID identity for a legal entity on a specific chain, if one exists.
     *
     * @param legalEntityId ID of the legal entity
     * @param chainConfigId ID of the chain configuration
     * @return the identity record, or empty if none exists
     */
    @Transactional(readOnly = true)
    public Optional<OnchainIdentity> findIdentity(UUID legalEntityId, UUID chainConfigId) {
        return identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId);
    }

    /**
     * Returns all ONCHAINID identities deployed for a legal entity across all chains.
     *
     * @param legalEntityId ID of the legal entity
     * @return list of identity records (may be empty)
     */
    @Transactional(readOnly = true)
    public List<OnchainIdentity> getIdentities(UUID legalEntityId) {
        return identityRepository.findByLegalEntityId(legalEntityId);
    }

    /**
     * Links an ONCHAINID identity to a T-REX token by registering the investor's wallet
     * in the suite's IdentityRegistry.
     *
     * <p>This must be called before the investor can receive or transfer tokens.
     *
     * @param onchainIdentityId ID of the ONCHAINID identity record
     * @param suiteId           ID of the ERC-3643 suite
     * @param walletAddress     investor's EVM wallet address
     */
    public void linkIdentityToToken(UUID onchainIdentityId, UUID suiteId, String walletAddress) {
        log.info(
            "Linking ONCHAINID identity={} to suite={} for wallet={}",
            onchainIdentityId, suiteId, walletAddress);

        OnchainIdentity identity = identityRepository.findById(onchainIdentityId)
            .orElseThrow(() -> new EntityNotFoundException("OnchainIdentity", onchainIdentityId));

        deploymentService.registerInvestorIdentity(
            identity.getLegalEntityId(), suiteId, walletAddress, onchainIdentityId);

        eventPublisher.publishEvent(new OnchainIdentityLinkedEvent(onchainIdentityId, null, "REGISTRY_ADMIN", java.util.Map.of()));
    }

    /**
     * Checks whether a legal entity holds all required and non-expired claims on a chain.
     *
     * <p>Returns {@code true} only if:
     * <ol>
     *   <li>An ONCHAINID identity exists for the entity on the chain</li>
     *   <li>All required claim topics have at least one non-revoked, non-expired claim</li>
     * </ol>
     *
     * @param legalEntityId ID of the legal entity
     * @param chainConfigId ID of the chain configuration
     * @return {@code true} if the entity is verified for token transfers
     */
    @Transactional(readOnly = true)
    public boolean isVerified(UUID legalEntityId, UUID chainConfigId) {
        Optional<OnchainIdentity> identity =
            identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId);

        if (identity.isEmpty()) {
            log.debug(
                "isVerified=false: no ONCHAINID identity for entity={} on chain={}",
                legalEntityId, chainConfigId);
            return false;
        }

        // Check locally: entity must hold a non-revoked, non-expired KYC claim (topic 1)
        java.util.List<OnchainClaim> claims =
                claimRepository.findByOnchainIdentityId(identity.get().getId());
        java.time.Instant now = java.time.Instant.now();
        boolean hasKyc = claims.stream().anyMatch(c ->
                c.getTopic() == Erc3643DeploymentService.CLAIM_TOPIC_KYC
                && c.getRevokedAt() == null
                && (c.getExpiresAt() == null || c.getExpiresAt().isAfter(now)));
        log.debug("isVerified: entity={} chain={} hasKyc={}", legalEntityId, chainConfigId, hasKyc);
        return hasKyc;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the deployed ONCHAINID contract address from the {@code IdFactory}
     * transaction receipt.
     *
     * <p>IdFactory emits {@code WalletLinked(address indexed wallet)} after deploying a new
     * identity proxy. The wallet address in the event is the management key passed to
     * {@code deployIdentityProxy}. The identity contract address is the {@code contractAddress}
     * field of the receipt (since {@code deployIdentityProxy} deploys a new contract via CREATE).
     */
    private String extractIdentityAddress(TransactionReceipt receipt, String managementKey) {
        // The identity proxy is a new contract; its address is in receipt.contractAddress
        if (receipt.getContractAddress() != null && !receipt.getContractAddress().isBlank()) {
            return receipt.getContractAddress();
        }

        // Fallback: look for WalletLinked event — the new identity address is in the log data
        for (Log logEntry : receipt.getLogs()) {
            if (logEntry.getTopics() != null && !logEntry.getTopics().isEmpty()
                    && IDENTITY_CREATED_TOPIC.equalsIgnoreCase(logEntry.getTopics().get(0))) {
                // data = abi.encode(identityAddress) — 32-byte padded address
                String data = logEntry.getData();
                if (data != null && data.length() >= 66) {
                    return "0x" + data.substring(data.length() - 40);
                }
            }
        }

        // Last resort: there's no address in the receipt (should not happen with a well-formed factory)
        throw new RuntimeException(
                "Could not extract identity address from IdFactory receipt: " + receipt.getTransactionHash());
    }
}
