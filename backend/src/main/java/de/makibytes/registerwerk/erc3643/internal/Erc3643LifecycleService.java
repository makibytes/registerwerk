package de.makibytes.registerwerk.erc3643.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import de.makibytes.registerwerk.erc3643.events.ComplianceModuleAddedEvent;
import de.makibytes.registerwerk.erc3643.events.ComplianceModuleRemovedEvent;
import de.makibytes.registerwerk.erc3643.events.TrustedIssuerAddedEvent;
import de.makibytes.registerwerk.erc3643.events.TrustedIssuerRemovedEvent;
import de.makibytes.registerwerk.erc3643.events.ClaimTopicAddedEvent;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopic;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModule;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuer;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModuleRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.erc3643.web.dto.ComplianceStatusResponse;

/**
 * Manages ERC-3643 token lifecycle operations beyond initial suite deployment.
 *
 * <p>Covers:
 * <ul>
 *   <li>Compliance module management (add / remove pluggable rule modules)</li>
 *   <li>Trusted issuer management (add / remove claim issuers)</li>
 *   <li>Claim topic management (add required claim topics)</li>
 *   <li>Agent-only regulatory operations (forced transfer, address freeze / unfreeze)</li>
 * </ul>
 *
 */
@Service
@Transactional
public class Erc3643LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(Erc3643LifecycleService.class);

    private final Erc3643SuiteRepository suiteRepository;
    private final Erc3643ComplianceModuleRepository complianceModuleRepository;
    private final Erc3643TrustedIssuerRepository trustedIssuerRepository;
    private final Erc3643ClaimTopicRepository claimTopicRepository;
    private final Erc3643IdentityRegistryRepository identityRegistryRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EvmContractService evmContractService;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final BlockchainTransactionService txService;
    private final HolderBlockGate holderBlockGate;

    public Erc3643LifecycleService(
            Erc3643SuiteRepository suiteRepository,
            Erc3643ComplianceModuleRepository complianceModuleRepository,
            Erc3643TrustedIssuerRepository trustedIssuerRepository,
            Erc3643ClaimTopicRepository claimTopicRepository,
            Erc3643IdentityRegistryRepository identityRegistryRepository,
            AssetDeploymentRepository deploymentRepository,
            ApplicationEventPublisher eventPublisher,
            EvmContractService evmContractService,
            BlockchainClientRegistry blockchainClientRegistry,
            BlockchainTransactionService txService,
            HolderBlockGate holderBlockGate) {
        this.suiteRepository = suiteRepository;
        this.complianceModuleRepository = complianceModuleRepository;
        this.trustedIssuerRepository = trustedIssuerRepository;
        this.claimTopicRepository = claimTopicRepository;
        this.identityRegistryRepository = identityRegistryRepository;
        this.deploymentRepository = deploymentRepository;
        this.eventPublisher = eventPublisher;
        this.evmContractService = evmContractService;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.txService = txService;
        this.holderBlockGate = holderBlockGate;
    }

    // ── Shared on-chain helpers ────────────────────────────────────────────────

    /**
     * Sends a non-tracked transaction to a suite contract (for management operations like
     * adding compliance modules, trusted issuers, claim topics). Uses synchronous send.
     *
     * <p>Throws rather than silently no-opping when the target address is missing: since
     * {@code Erc3643DeploymentService} backfills all six suite addresses (via {@code
     * EwpgTREXFactory.getSuiteAddresses}) before ever persisting an {@code Erc3643Suite} row —
     * and those columns are DB {@code NOT NULL} — a suite existing at all means every address is
     * genuinely set. Reaching this branch means something is actually broken (a legacy
     * pre-migration row, or a data-integrity bug), not a normal "still deploying" state, so
     * operators must not be left believing an action succeeded when it never reached the chain.
     */
    private void sendToSuite(Erc3643Suite suite, String contractAddress, Function fn) {
        if (contractAddress == null || contractAddress.isBlank()
                || contractAddress.startsWith("0x-PENDING")) {
            throw new IllegalStateException("Suite " + suite.getId()
                    + " has no contract address for " + fn.getName() + " — cannot submit on-chain call");
        }
        AssetDeployment dep = deploymentRepository.findById(suite.getAssetDeploymentId())
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment",
                        suite.getAssetDeploymentId()));
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        org.web3j.protocol.Web3j web3j = blockchainClientRegistry.getEvmClient(descriptor);
        org.web3j.crypto.Credentials creds = evmContractService.credentials(descriptor);
        evmContractService.send(web3j, creds, contractAddress, fn);
    }

    /**
     * Submits a transaction to a suite contract asynchronously and returns a tracking UUID.
     * Returns null if the contract address is not yet set.
     *
     * <p>Publishes a {@link TokenAdminActionEvent} to the audit log for every call — the
     * chokepoint every correction operation in this class funnels through (forcedTransfer,
     * freeze/unfreeze(+partial), pause/unpause, forceBurn, batch*), keyed by the underlying
     * {@code assetDeploymentId} so it appears alongside plain-ERC-20/721/1155 admin actions
     * on the same deployment's audit trail.
     */
    private UUID submitToSuite(Erc3643Suite suite, String contractAddress, Function fn,
                               Map<String, Object> params, UUID actorId, String actorRole) {
        if (contractAddress == null || contractAddress.isBlank()
                || contractAddress.startsWith("0x-PENDING")) {
            // See sendToSuite's javadoc for why this throws rather than silently returning null.
            throw new IllegalStateException("Suite " + suite.getId()
                    + " has no contract address for " + fn.getName() + " — cannot submit on-chain call");
        }
        AssetDeployment dep = deploymentRepository.findById(suite.getAssetDeploymentId())
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment",
                        suite.getAssetDeploymentId()));
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = blockchainClientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, contractAddress, fn);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), fn.getName(), actorId, actorRole, params));

        return txService.record(txHash, fn.getName(), dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), contractAddress, params);
    }

    /**
     * Adds a compliance module to the suite's ModularCompliance contract and records it in the DB.
     *
     * @param suiteId       ID of the ERC-3643 suite
     * @param moduleAddress on-chain address of the compliance module contract
     * @param moduleType    logical type identifier, e.g. "MAX_BALANCE", "COUNTRY_RESTRICT"
     * @param params        module configuration parameters (stored as JSONB)
     */
    public void addComplianceModule(
            UUID suiteId, String moduleAddress, String moduleType, Map<String, Object> params,
            UUID actorId, String actorRole) {
        log.info(
            "Adding compliance module type={} at address={} to suite={}",
            moduleType, moduleAddress, suiteId);

        Erc3643Suite suite = requireSuite(suiteId);

        // IModularCompliance.addModule(address moduleToAdd)
        Function fn = new Function(
                "addModule",
                List.of(new Address(moduleAddress)),
                List.of()
        );
        sendToSuite(suite, suite.getComplianceAddress(), fn);
        configureComplianceModule(suite, moduleAddress, params);

        Erc3643ComplianceModule module = new Erc3643ComplianceModule();
        module.setSuiteId(suiteId);
        module.setModuleAddress(moduleAddress);
        module.setModuleType(moduleType);
        module.setParameters(params);
        module.setAddedAt(Instant.now());

        // Extract structured config from params map (mirrors EwpgModularCompliance.TokenConfig fields)
        if (params.containsKey("maxInvestors")) {
            module.setMaxInvestors(((Number) params.get("maxInvestors")).intValue());
        }
        if (params.containsKey("maxBalance")) {
            module.setMaxBalance(new java.math.BigInteger(params.get("maxBalance").toString()));
        }
        if (params.containsKey("transferCooldown")) {
            module.setTransferCooldown(((Number) params.get("transferCooldown")).intValue());
        }
        if (params.containsKey("blockedCountries") && params.get("blockedCountries") instanceof List<?> rawList) {
            module.setBlockedCountries(rawList.stream()
                    .map(v -> ((Number) v).shortValue())
                    .toList());
        }

        Erc3643ComplianceModule saved = complianceModuleRepository.save(module);

        eventPublisher.publishEvent(new ComplianceModuleAddedEvent(suiteId, actorId, actorRole,
                Map.of("moduleAddress", moduleAddress, "moduleType", moduleType)));
    }

    /**
     * Removes a compliance module from the suite's ModularCompliance contract and soft-deletes the DB record.
     *
     * @param suiteId  ID of the ERC-3643 suite
     * @param moduleId ID of the {@link Erc3643ComplianceModule} record to remove
     */
    public void removeComplianceModule(UUID suiteId, UUID moduleId, UUID actorId, String actorRole) {
        log.info("Removing compliance module={} from suite={}", moduleId, suiteId);

        requireSuite(suiteId);

        Erc3643ComplianceModule module = complianceModuleRepository.findById(moduleId)
            .orElseThrow(() -> new EntityNotFoundException("Erc3643ComplianceModule", moduleId));

        if (!module.getSuiteId().equals(suiteId)) {
            throw new IllegalArgumentException(
                "ComplianceModule " + moduleId + " does not belong to suite " + suiteId);
        }

        // IModularCompliance.removeModule(address moduleToRemove)
        Erc3643Suite suite2 = requireSuite(suiteId);
        Function fnRemove = new Function(
                "removeModule",
                List.of(new Address(module.getModuleAddress())),
                List.of()
        );
        sendToSuite(suite2, suite2.getComplianceAddress(), fnRemove);

        module.setRemovedAt(Instant.now());
        complianceModuleRepository.save(module);

        eventPublisher.publishEvent(new ComplianceModuleRemovedEvent(suiteId, actorId, actorRole,
                Map.of("moduleId", moduleId, "moduleAddress", module.getModuleAddress())));
    }

    /**
     * Registers a trusted claim issuer in the suite's TrustedIssuersRegistry.
     *
     * @param suiteId       ID of the ERC-3643 suite
     * @param issuerAddress on-chain address of the issuer (EOA or contract)
     * @param claimTopics   list of claim topic numbers this issuer is authorised to sign
     */
    public void addTrustedIssuer(UUID suiteId, String issuerAddress, List<Long> claimTopics, UUID legalEntityId,
                                 UUID actorId, String actorRole) {
        log.info(
            "Adding trusted issuer={} for topics={} to suite={}",
            issuerAddress, claimTopics, suiteId);

        Erc3643Suite suiteForIssuer = requireSuite(suiteId);

        // ITrustedIssuersRegistry.addTrustedIssuer(address _trustedIssuer, uint256[] claimTopics)
        List<Uint256> topicList = claimTopics.stream()
                .map(t -> new Uint256(java.math.BigInteger.valueOf(t)))
                .toList();
        Function fnAddIssuer = new Function(
                "addTrustedIssuer",
                List.of(new Address(issuerAddress), new DynamicArray<>(Uint256.class, topicList)),
                List.of()
        );
        sendToSuite(suiteForIssuer, suiteForIssuer.getTrustedIssuersRegistry(), fnAddIssuer);

        Erc3643TrustedIssuer issuer = new Erc3643TrustedIssuer();
        issuer.setSuiteId(suiteId);
        issuer.setIssuerAddress(issuerAddress);
        issuer.setClaimTopics(claimTopics);
        issuer.setLegalEntityId(legalEntityId);
        issuer.setAddedAt(Instant.now());
        Erc3643TrustedIssuer saved = trustedIssuerRepository.save(issuer);

        eventPublisher.publishEvent(new TrustedIssuerAddedEvent(suiteId, actorId, actorRole,
                Map.of("issuerAddress", issuerAddress, "claimTopics", claimTopics)));
    }

    /**
     * Removes a trusted issuer from the suite's TrustedIssuersRegistry and soft-deletes the DB record.
     *
     * @param suiteId  ID of the ERC-3643 suite
     * @param issuerId ID of the {@link Erc3643TrustedIssuer} record to remove
     */
    public void removeTrustedIssuer(UUID suiteId, UUID issuerId, UUID actorId, String actorRole) {
        log.info("Removing trusted issuer={} from suite={}", issuerId, suiteId);

        requireSuite(suiteId);

        Erc3643TrustedIssuer issuer = trustedIssuerRepository.findById(issuerId)
            .orElseThrow(() -> new EntityNotFoundException("Erc3643TrustedIssuer", issuerId));

        if (!issuer.getSuiteId().equals(suiteId)) {
            throw new IllegalArgumentException(
                "TrustedIssuer " + issuerId + " does not belong to suite " + suiteId);
        }

        // ITrustedIssuersRegistry.removeTrustedIssuer(address _trustedIssuer)
        Erc3643Suite suiteForRemoveIssuer = requireSuite(suiteId);
        Function fnRemoveIssuer = new Function(
                "removeTrustedIssuer",
                List.of(new Address(issuer.getIssuerAddress())),
                List.of()
        );
        sendToSuite(suiteForRemoveIssuer, suiteForRemoveIssuer.getTrustedIssuersRegistry(), fnRemoveIssuer);

        issuer.setRemovedAt(Instant.now());
        trustedIssuerRepository.save(issuer);

        eventPublisher.publishEvent(new TrustedIssuerRemovedEvent(suiteId, actorId, actorRole,
                Map.of("issuerId", issuerId, "issuerAddress", issuer.getIssuerAddress())));
    }

    /**
     * Registers a required claim topic in the suite's ClaimTopicsRegistry.
     *
     * <p>All investors must hold a valid claim for every registered topic before transfers
     * are permitted.
     *
     * @param suiteId ID of the ERC-3643 suite
     * @param topic   claim topic number
     * @param label   human-readable label (e.g. "KYC")
     */
    public void addRequiredClaimTopic(UUID suiteId, long topic, String label, UUID actorId, String actorRole) {
        log.info("Adding required claim topic={} ({}) to suite={}", topic, label, suiteId);

        Erc3643Suite suiteForTopic = requireSuite(suiteId);

        // IClaimTopicsRegistry.addClaimTopic(uint256 claimTopic)
        Function fnAddTopic = new Function(
                "addClaimTopic",
                List.of(new Uint256(java.math.BigInteger.valueOf(topic))),
                List.of()
        );
        sendToSuite(suiteForTopic, suiteForTopic.getClaimTopicsRegistry(), fnAddTopic);

        Erc3643ClaimTopic claimTopic = new Erc3643ClaimTopic();
        claimTopic.setSuiteId(suiteId);
        claimTopic.setTopic(topic);
        claimTopic.setLabel(label);
        Erc3643ClaimTopic saved = claimTopicRepository.save(claimTopic);

        eventPublisher.publishEvent(new ClaimTopicAddedEvent(suiteId, actorId, actorRole,
                Map.of("topic", topic, "label", label)));
    }

    /**
     * Executes an agent-only forced transfer of tokens, bypassing compliance restrictions.
     *
     * <p>Reserved for regulatory enforcement scenarios (e.g. court-ordered asset seizure,
     * lost-wallet recovery). Requires the calling wallet to hold the AGENT role on the
     * T-REX token contract.
     *
     * @param suiteId ID of the ERC-3643 suite
     * @param from    source wallet address
     * @param to      destination wallet address
     * @param amount  token amount to transfer (in token decimals)
     * @param reason  textual reason for the forced transfer (logged in audit)
     */
    public UUID forcedTransfer(UUID suiteId, String from, String to, BigDecimal amount, String reason,
                               UUID actorId, String actorRole) {
        log.info("Forced transfer on suite={}: from={} to={} amount={}", suiteId, from, to, amount);
        requireNotBlocked(from);
        requireNotBlocked(to);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function(forcedTransferMethodName(),
                List.of(new Address(from), new Address(to), new Uint256(amount.toBigIntegerExact())),
                List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("from", from, "to", to, "amount", amount.toPlainString(), "reason", reason),
                actorId, actorRole);
    }

    /**
     * T-REX has no native forcedApprove agent operation — this calls the Registerwerk-specific
     * extension added directly on {@code EwpgERC3643} (mirrors T-REX's own {@code forcedTransfer},
     * same {@code onlyAgent} gate), which sets the ERC-20 allowance via the inherited
     * {@code _approve} and emits a locally-declared {@code ForcedApprove} event (see the
     * NatSpec on {@code EwpgERC3643.forcedApprove} for why the event isn't imported from
     * {@code IEwpgAdminControls} — a pragma-version conflict with the T-REX base).
     */
    public UUID forcedApprove(UUID suiteId, String owner, String spender, BigDecimal amount, String reason,
                              UUID actorId, String actorRole) {
        log.info("Forced approve on suite={}: owner={} spender={} amount={}", suiteId, owner, spender, amount);
        requireNotBlocked(owner);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function(forcedApproveMethodName(),
                List.of(new Address(owner), new Address(spender), new Uint256(amount.toBigIntegerExact()),
                        new org.web3j.abi.datatypes.Utf8String(reason)),
                List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("owner", owner, "spender", spender, "amount", amount.toPlainString(), "reason", reason),
                actorId, actorRole);
    }

    public UUID freezeAddress(UUID suiteId, String address, UUID actorId, String actorRole) {
        log.info("Freezing address={} on suite={}", address, suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("setAddressFrozen",
                List.of(new Address(address), new Bool(true)), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn, Map.of("address", address), actorId, actorRole);
    }

    public UUID unfreezeAddress(UUID suiteId, String address, UUID actorId, String actorRole) {
        log.info("Unfreezing address={} on suite={}", address, suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("setAddressFrozen",
                List.of(new Address(address), new Bool(false)), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn, Map.of("address", address), actorId, actorRole);
    }

    /**
     * Freezes a partial amount of tokens for an investor (T-REX agent operation).
     * The frozen tokens cannot be transferred even though the address is not fully frozen.
     */
    public UUID freezePartialTokens(UUID suiteId, String address, BigDecimal amount, UUID actorId, String actorRole) {
        log.info("Freeze partial tokens={} for address={} on suite={}", amount, address, suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("freezePartialTokens",
                List.of(new Address(address), new Uint256(amount.toBigIntegerExact())), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("address", address, "amount", amount.toPlainString()), actorId, actorRole);
    }

    /**
     * Unfreezes a partial amount of previously frozen tokens for an investor.
     */
    public UUID unfreezePartialTokens(UUID suiteId, String address, BigDecimal amount, UUID actorId, String actorRole) {
        log.info("Unfreeze partial tokens={} for address={} on suite={}", amount, address, suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("unfreezePartialTokens",
                List.of(new Address(address), new Uint256(amount.toBigIntegerExact())), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("address", address, "amount", amount.toPlainString()), actorId, actorRole);
    }

    public UUID pause(UUID suiteId, UUID actorId, String actorRole) {
        log.info("Pausing ERC-3643 token on suite={}", suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("pause", List.of(), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("tokenAddress", suite.getTokenAddress() != null ? suite.getTokenAddress() : ""), actorId, actorRole);
    }

    public UUID unpause(UUID suiteId, UUID actorId, String actorRole) {
        log.info("Unpausing ERC-3643 token on suite={}", suiteId);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("unpause", List.of(), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("tokenAddress", suite.getTokenAddress() != null ? suite.getTokenAddress() : ""), actorId, actorRole);
    }

    public UUID forceBurn(UUID suiteId, String from, java.math.BigDecimal amount, String legalBasis,
                          UUID actorId, String actorRole) {
        log.info("forceBurn from={} amount={} on suite={}", from, amount, suiteId);
        requireNotBlocked(from);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("burn",
                List.of(new Address(from), new Uint256(amount.toBigIntegerExact())), List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("from", from, "amount", amount.toPlainString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    /**
     * Batch forced transfer — T-REX agent operation for transferring from multiple holders at once.
     *
     * @param suiteId IDs of the ERC-3643 suite
     * @param froms   list of source addresses (same length as {@code tos} and {@code amounts})
     * @param tos     list of destination addresses
     * @param amounts list of transfer amounts in token decimals
     */
    public UUID batchForcedTransfer(UUID suiteId, List<String> froms, List<String> tos,
                                    List<BigDecimal> amounts, UUID actorId, String actorRole) {
        log.info("batchForcedTransfer {} entries on suite={}", froms.size(), suiteId);
        froms.forEach(this::requireNotBlocked);
        tos.forEach(this::requireNotBlocked);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("batchForcedTransfer",
                List.of(
                        new DynamicArray<>(Address.class, froms.stream().map(Address::new).toList()),
                        new DynamicArray<>(Address.class, tos.stream().map(Address::new).toList()),
                        new DynamicArray<>(Uint256.class,
                                amounts.stream().map(a -> new Uint256(a.toBigIntegerExact())).toList())
                ),
                List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("count", froms.size(), "froms", froms, "tos", tos), actorId, actorRole);
    }

    /**
     * Batch mint — T-REX agent operation for minting tokens to multiple addresses at once.
     */
    public UUID batchMint(UUID suiteId, List<String> toAddresses, List<BigDecimal> amounts, UUID actorId, String actorRole) {
        log.info("batchMint {} entries on suite={}", toAddresses.size(), suiteId);
        toAddresses.forEach(this::requireNotBlocked);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("batchMint",
                List.of(
                        new DynamicArray<>(Address.class, toAddresses.stream().map(Address::new).toList()),
                        new DynamicArray<>(Uint256.class,
                                amounts.stream().map(a -> new Uint256(a.toBigIntegerExact())).toList())
                ),
                List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("count", toAddresses.size(), "addresses", toAddresses), actorId, actorRole);
    }

    /**
     * Batch burn — T-REX agent operation for burning tokens from multiple addresses at once.
     */
    public UUID batchBurn(UUID suiteId, List<String> userAddresses, List<BigDecimal> amounts, UUID actorId, String actorRole) {
        log.info("batchBurn {} entries on suite={}", userAddresses.size(), suiteId);
        userAddresses.forEach(this::requireNotBlocked);
        Erc3643Suite suite = requireSuite(suiteId);
        Function fn = new Function("batchBurn",
                List.of(
                        new DynamicArray<>(Address.class, userAddresses.stream().map(Address::new).toList()),
                        new DynamicArray<>(Uint256.class,
                                amounts.stream().map(a -> new Uint256(a.toBigIntegerExact())).toList())
                ),
                List.of());
        return submitToSuite(suite, suite.getTokenAddress(), fn,
                Map.of("count", userAddresses.size(), "addresses", userAddresses), actorId, actorRole);
    }

    /**
     * Returns the T-REX suite for a given asset deployment ID.
     *
     * @param assetDeploymentId ID of the asset deployment
     * @return the suite record
     * @throws EntityNotFoundException if no suite exists for the given deployment
     */
    @Transactional(readOnly = true)
    public Erc3643Suite getSuiteDetails(UUID assetDeploymentId) {
        return suiteRepository.findByAssetDeploymentId(assetDeploymentId)
            .orElseThrow(() -> new EntityNotFoundException(
                "Erc3643Suite for assetDeployment", assetDeploymentId));
    }

    /**
     * Returns the T-REX suite for a given asset deployment ID, verifying the deployment
     * actually belongs to {@code assetId} first.
     *
     * <p>{@code Erc3643Controller} authorizes every endpoint against the path's {@code #assetId}
     * (e.g. {@code @assetAccessChecker.canRead(#assetId, ...)}), but every method body used to
     * resolve the suite purely from {@code deploymentId} — an attacker-controlled path variable
     * independent of the authorized {@code assetId}. A caller authorized against their own asset
     * could supply a different tenant's {@code deploymentId} and operate on that suite instead
     * (identity registry reads/writes, forced-transfer, freeze, …). This is the single chokepoint
     * every controller method funnels through, so fixing it here closes the hole for all of them
     * at once rather than patching ~18 call sites individually.
     *
     * @throws EntityNotFoundException if no suite exists for the given deployment, OR the
     *                                 deployment does not belong to {@code assetId} — the two
     *                                 cases are deliberately indistinguishable to the caller
     */
    @Transactional(readOnly = true)
    public Erc3643Suite getSuiteDetails(UUID assetId, UUID assetDeploymentId) {
        AssetDeployment deployment = deploymentRepository.findById(assetDeploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", assetDeploymentId));
        if (!deployment.getAssetId().equals(assetId)) {
            throw new EntityNotFoundException("AssetDeployment", assetDeploymentId);
        }
        return getSuiteDetails(assetDeploymentId);
    }

    /**
     * Returns aggregated compliance state for a suite: investor count vs limits,
     * blocked countries, and the list of active compliance modules.
     */
    @Transactional(readOnly = true)
    public ComplianceStatusResponse getComplianceStatus(UUID suiteId) {
        requireSuite(suiteId);

        var activeModules = complianceModuleRepository.findBySuiteIdAndRemovedAtIsNull(suiteId);
        int investorCount = (int) identityRegistryRepository.findBySuiteIdAndRemovedAtIsNull(suiteId).size();

        // Aggregate structured config from active modules
        Integer maxInvestors = null;
        java.math.BigInteger maxBalance = null;
        Integer transferCooldown = null;
        List<Short> blockedCountries = null;

        for (var m : activeModules) {
            if (m.getMaxInvestors() != null) maxInvestors = m.getMaxInvestors();
            if (m.getMaxBalance() != null) maxBalance = m.getMaxBalance();
            if (m.getTransferCooldown() != null) transferCooldown = m.getTransferCooldown();
            if (m.getBlockedCountries() != null && !m.getBlockedCountries().isEmpty()) {
                blockedCountries = m.getBlockedCountries();
            }
        }

        var moduleInfos = activeModules.stream()
                .map(m -> new ComplianceStatusResponse.ModuleInfo(
                        m.getId(), m.getModuleAddress(), m.getModuleType(), true))
                .toList();

        return new ComplianceStatusResponse(
                suiteId, investorCount, maxInvestors, maxBalance,
                transferCooldown, blockedCountries != null ? blockedCountries : List.of(),
                moduleInfos);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * §16 eWpG Sperrvermerk — a wallet under an active legal block must not be forced-transferred,
     * force-approved, force-burned, or minted to via any agent-only T-REX operation, regardless of
     * on-chain compliance module state (these operations bypass the compliance modules entirely).
     * Checked by wallet address only, mirroring {@code TokenAdminService}'s equivalent gate.
     */
    private void requireNotBlocked(String walletAddress) {
        if (holderBlockGate.isBlocked(null, walletAddress)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Wallet " + walletAddress + " is subject to an active §16 eWpG Sperrvermerk "
                    + "(legal block) — operation refused.");
        }
    }

    /**
     * Sends on-chain configuration calls to a newly added compliance module.
     * Each recognised param key maps to one EwpgModularCompliance setter.
     */
    private void configureComplianceModule(Erc3643Suite suite, String moduleAddress,
                                           Map<String, Object> params) {
        if (params.containsKey("maxInvestors")) {
            int v = ((Number) params.get("maxInvestors")).intValue();
            sendToSuite(suite, moduleAddress, new Function("setMaxInvestors",
                    List.of(new Uint256(java.math.BigInteger.valueOf(v))), List.of()));
        }
        if (params.containsKey("maxBalance")) {
            java.math.BigInteger v = new java.math.BigInteger(params.get("maxBalance").toString());
            sendToSuite(suite, moduleAddress, new Function("setMaxBalance",
                    List.of(new Uint256(v)), List.of()));
        }
        if (params.containsKey("transferCooldown")) {
            int v = ((Number) params.get("transferCooldown")).intValue();
            sendToSuite(suite, moduleAddress, new Function("setTransferCooldown",
                    List.of(new Uint256(java.math.BigInteger.valueOf(v))), List.of()));
        }
        if (params.containsKey("blockedCountries") && params.get("blockedCountries") instanceof List<?> rawList) {
            for (Object entry : rawList) {
                int country = ((Number) entry).intValue();
                sendToSuite(suite, moduleAddress, new Function("blockCountry",
                        List.of(new Uint16(java.math.BigInteger.valueOf(country & 0xFFFF))), List.of()));
            }
        }
    }

    private Erc3643Suite requireSuite(UUID suiteId) {
        return suiteRepository.findById(suiteId)
            .orElseThrow(() -> new EntityNotFoundException("Erc3643Suite", suiteId));
    }

    private static String forcedTransferMethodName() {
        // T-REX IToken.forcedTransfer(address _from, address _to, uint256 _amount)
        return "forcedTransfer";
    }

    private static String forcedApproveMethodName() {
        // EwpgERC3643.forcedApprove(address owner, address spender, uint256 amount, string legalBasis)
        return "forcedApprove";
    }
}
