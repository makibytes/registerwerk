package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.erc3643.events.Erc3643SuiteDeployedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopic;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.TypeReference;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service responsible for deploying ERC-3643 (T-REX) security token suites on-chain.
 *
 * <p>Each T-REX suite consists of six contracts:
 * <ol>
 *   <li>Token – ERC-20-compatible security token with compliance hooks</li>
 *   <li>IdentityRegistry – maps wallet addresses to ONCHAINID identities</li>
 *   <li>IdentityRegistryStorage – persistent storage for the identity registry</li>
 *   <li>ClaimTopicsRegistry – enumerates required claim topics for all holders</li>
 *   <li>TrustedIssuersRegistry – whitelists claim issuers per topic</li>
 *   <li>ModularCompliance – pluggable rule engine for transfer restrictions</li>
 * </ol>
 *
 * <p>The deployment uses the configured eWpG T-REX factory ABI directly and resolves the
 * factory-created suite addresses on-chain. It never invents placeholder suite addresses.
 */
@Service
@Transactional
public class Erc3643DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc3643DeploymentService.class);

    /** Standard T-REX claim topic: Know Your Customer. */
    public static final long CLAIM_TOPIC_KYC = 1L;

    /** Standard T-REX claim topic: Anti-Money Laundering. */
    public static final long CLAIM_TOPIC_AML = 2L;

    /**
     * keccak256("EwpgSuiteDeployed(bytes32,address,address,address)") — topic0 of
     * {@code EwpgTREXFactory.EwpgSuiteDeployed} event used to extract contract addresses.
     */
    private static final String REGISTERWERK_SUITE_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String(
                    "EwpgSuiteDeployed(bytes32,address,address,address)");

    private final BlockchainClientRegistry clientRegistry;
    private final OnchainIdentityRepository identityRepository;
    private final OnchainClaimRepository claimRepository;
    private final Erc3643SuiteRepository suiteRepository;
    private final Erc3643ClaimTopicRepository claimTopicRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetLookupPort assetLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final ChainConfigRepository chainConfigRepository;
    private final EvmContractService evmContractService;
    private final DurableEvmTransactionGateway evmTransactions;
    private final ContractAddressConfig contractAddressConfig;
    private final ClaimSigningService claimSigningService;
    private final BlockchainTransactionService blockchainTransactionService;

    public Erc3643DeploymentService(
            BlockchainClientRegistry clientRegistry,
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository,
            Erc3643SuiteRepository suiteRepository,
            Erc3643ClaimTopicRepository claimTopicRepository,
            AssetDeploymentRepository deploymentRepository,
            AssetLookupPort assetLookupPort,
            ApplicationEventPublisher eventPublisher,
            ExplorerUrlBuilder explorerUrlBuilder,
            ChainConfigRepository chainConfigRepository,
            EvmContractService evmContractService,
            DurableEvmTransactionGateway evmTransactions,
            ContractAddressConfig contractAddressConfig,
            ClaimSigningService claimSigningService,
            BlockchainTransactionService blockchainTransactionService) {
        this.clientRegistry = clientRegistry;
        this.identityRepository = identityRepository;
        this.claimRepository = claimRepository;
        this.suiteRepository = suiteRepository;
        this.claimTopicRepository = claimTopicRepository;
        this.deploymentRepository = deploymentRepository;
        this.assetLookupPort = assetLookupPort;
        this.eventPublisher = eventPublisher;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.chainConfigRepository = chainConfigRepository;
        this.evmContractService = evmContractService;
        this.evmTransactions = evmTransactions;
        this.contractAddressConfig = contractAddressConfig;
        this.claimSigningService = claimSigningService;
        this.blockchainTransactionService = blockchainTransactionService;
    }

    /**
     * Deploys (or adopts) the full T-REX suite for one {@code asset_deployment} row (T3-19).
     *
     * <p>The row is looked up by its id — an asset can have several rows for the same chain
     * (earlier FAILED attempts), so a lookup by (asset, chainConfig) is ambiguous. The factory's
     * suite for this asset is deterministic ({@code salt = "registerwerk-" + assetId}) and the
     * factory refuses a second suite per assetId, so a lost response (receipt wait timed out, RPC
     * dropped) used to leave an orphan suite on chain and the asset undeployable. Now:
     * <ol>
     *   <li>before sending, and after any send/receipt failure, {@code getSuiteAddresses(salt)} is
     *       checked; an existing suite is adopted when its token reports our {@code assetId()},
     *       has our signer as agent and is owned by us or (pending acceptance) by the inner
     *       TREXFactory — otherwise it fails loudly;</li>
     *   <li>the broadcast tx hash is persisted on the deployment row before waiting for the
     *       receipt, and a receipt that does not arrive in time leaves the row PENDING with that
     *       hash, so {@code AssetDeploymentService.pollPendingDeploymentConfirmations} confirms
     *       it and then calls {@link #recordSuiteForDeployment} to persist the suite.</li>
     * </ol>
     *
     * @param deploymentId  the PENDING {@code asset_deployment} row this suite belongs to
     * @param assetId       asset being deployed (must match the row)
     * @param chain         chain descriptor (used only for legacy rows without chain_config_id)
     * @param ownerAddress  registry backend wallet address
     * @return future resolving to the deployment tx hash and — once the suite is known — its token
     *         address (null while the tx is still unmined)
     */
    public CompletableFuture<TokenDeploymentResult> deploy(
            UUID deploymentId, UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-3643 suite for asset={} deployment={} on chain={}", assetId, deploymentId, chain);
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = deploymentRepository.findById(deploymentId)
                    .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
            if (!assetId.equals(deployment.getAssetId())) {
                throw new IllegalStateException("AssetDeployment " + deploymentId + " belongs to asset "
                        + deployment.getAssetId() + ", not " + assetId);
            }
            ChainConfig chainConfig = resolveChainConfig(deployment, chain);

            String factoryAddress = contractAddressConfig.requireTrexFactory(chainConfig.getIdentifier());
            Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
            EvmSigner signer = evmContractService.signer(chainConfig.getId());
            if (ownerAddress == null || !EvmUtils.normalizeAddress(ownerAddress)
                    .equals(EvmUtils.normalizeAddress(signer.address()))) {
                throw new IllegalStateException(
                        "ERC-3643 owner must match the configured chain signer");
            }
            String salt = suiteSalt(assetId);
            byte[] assetIdBytes = EvmUtils.uuidToBytes32(assetId);

            Optional<AdoptedSuite> existing = adoptExistingSuite(web3j, signer, factoryAddress, salt,
                    assetIdBytes, assetId, deploymentId, null);
            if (existing.isPresent()) {
                return finishDeployment(chainConfig.getId(), web3j, signer, existing.get());
            }

            AssetLookupPort.AssetInfo assetInfo = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
            // Fails closed before broadcasting: a suite without a ClaimIssuer contract as trusted
            // issuer could never have a verified holder (T2-21).
            String claimIssuer = contractAddressConfig.requireClaimIssuer(chainConfig.getIdentifier());
            Function fn = buildDeployEwpgSuiteFunction(assetIdBytes, salt, ownerAddress, claimIssuer,
                    assetInfo.name(), deriveSymbol(assetInfo.name()));
            String txHash = evmContractService.submit(chainConfig.getId(), web3j, signer, factoryAddress, fn);
            recordBroadcastTx(deploymentId, txHash);

            TransactionReceipt receipt;
            try {
                receipt = evmContractService.waitForReceipt(web3j, txHash);
            } catch (Exception notMined) {
                // Not mined within the wait (or the receipt RPC failed): the tx may still land.
                // Adopt if it already has; otherwise stay PENDING with the persisted hash.
                Optional<AdoptedSuite> adopted = adoptAfterFailure(web3j, signer, factoryAddress, salt,
                        assetIdBytes, assetId, deploymentId, txHash, notMined);
                if (adopted.isPresent()) {
                    return finishDeployment(chainConfig.getId(), web3j, signer, adopted.get());
                }
                log.warn("ERC-3643 suite tx={} for deployment={} not mined yet ({}); leaving it PENDING for "
                        + "the confirmation poll", txHash, deploymentId, notMined.getMessage());
                return TokenDeploymentResult.txOnly(txHash);
            }
            if (!receipt.isStatusOK()) {
                RuntimeException reverted = new RuntimeException("Transaction reverted on-chain: tx="
                        + receipt.getTransactionHash() + " status=" + receipt.getStatus());
                // e.g. "assetId already deployed" because an earlier attempt's tx did land.
                return adoptAfterFailure(web3j, signer, factoryAddress, salt, assetIdBytes, assetId,
                        deploymentId, null, reverted)
                        .map(adopted -> finishDeployment(chainConfig.getId(), web3j, signer, adopted))
                        .orElseThrow(() -> reverted);
            }

            // The tx is mined and successful, so the suite exists — resolve its real addresses via
            // the factory's own getSuiteAddresses(salt) view call rather than parsing event-log topics.
            Erc3643Suite suite = resolveDeployedSuite(web3j, factoryAddress, salt, deploymentId);
            return finishDeployment(chainConfig.getId(), web3j, signer,
                    new AdoptedSuite(suite, receipt.getTransactionHash()));
        });
    }

    /**
     * Persists the suite of a deployment the confirmation poll has just CONFIRMED when the deploy
     * flow could not (receipt wait timed out, T3-19). Idempotent: a no-op when the suite row exists.
     * Verifies the on-chain suite exactly like adoption does.
     */
    public void recordSuiteForDeployment(UUID deploymentId) {
        if (suiteRepository.findByAssetDeploymentId(deploymentId).isPresent()) {
            return;
        }
        AssetDeployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        ChainConfig chainConfig = resolveChainConfig(deployment,
                new ChainDescriptor(deployment.getChain(), deployment.getNetwork()));
        String factoryAddress = contractAddressConfig.requireTrexFactory(chainConfig.getIdentifier());
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
        EvmSigner signer = evmContractService.signer(chainConfig.getId());
        UUID assetId = deployment.getAssetId();
        AdoptedSuite adopted = adoptExistingSuite(web3j, signer, factoryAddress, suiteSalt(assetId),
                EvmUtils.uuidToBytes32(assetId), assetId, deploymentId, deployment.getDeployedByTx())
                .orElseThrow(() -> new IllegalStateException("Deployment " + deploymentId
                        + " is confirmed but the factory reports no suite for asset " + assetId));
        finishDeployment(chainConfig.getId(), web3j, signer, adopted);
    }

    /** A suite found on chain (adopted or freshly deployed) with its creating transaction. */
    record AdoptedSuite(Erc3643Suite suite, String txHash) {}

    private static String suiteSalt(UUID assetId) {
        return "registerwerk-" + assetId;
    }

    private ChainConfig resolveChainConfig(AssetDeployment deployment, ChainDescriptor chain) {
        if (deployment.getChainConfigId() != null) {
            return chainConfigRepository.findById(deployment.getChainConfigId())
                    .orElseThrow(() -> new EntityNotFoundException("ChainConfig", deployment.getChainConfigId()));
        }
        // Legacy rows: resolve by stable chain prefix + network type (testnet identifiers are
        // network-specific, e.g. ETHEREUM_SEPOLIA).
        return chainConfigRepository
                .findByIdentifierStartingWith(chain.chain().name() + "_").stream()
                .filter(ChainConfig::isEnabled)
                .filter(c -> c.getNetworkType().name().equals(chain.network().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled ChainConfig found for descriptor=" + chain));
    }

    /**
     * Persists the broadcast hash before the receipt wait, so a slow tx is picked up by the
     * confirmation poll instead of being lost with the response (T3-19).
     */
    private void recordBroadcastTx(UUID deploymentId, String txHash) {
        deploymentRepository.findById(deploymentId).ifPresent(deployment -> {
            if (deployment.getDeploymentStatus() == AssetDeployment.DeploymentStatus.PENDING
                    && deployment.getDeployedByTx() == null) {
                deployment.setDeployedByTx(txHash);
                deploymentRepository.save(deployment);
            }
        });
    }

    private Optional<AdoptedSuite> adoptAfterFailure(Web3j web3j, EvmSigner signer, String factoryAddress,
                                                     String salt, byte[] assetIdBytes, UUID assetId,
                                                     UUID deploymentId, String knownTxHash,
                                                     Exception failure) {
        try {
            return adoptExistingSuite(web3j, signer, factoryAddress, salt, assetIdBytes, assetId,
                    deploymentId, knownTxHash);
        } catch (RuntimeException recheckFailure) {
            recheckFailure.addSuppressed(failure);
            throw recheckFailure;
        }
    }

    /**
     * Looks for this asset's suite under its deterministic salt and, if present, verifies it is
     * ours before adopting it: the token's {@code assetId()} must be ours, our signer must be a
     * token agent (set by {@code deployEwpgSuite}'s tokenAgents), and the token must be owned by
     * our signer or — ownership not yet accepted — by the inner TREXFactory. A mismatch fails
     * loudly: the slot is taken by something we did not deploy.
     *
     * @param knownTxHash the creating tx when already known; otherwise located via the factory's
     *                    {@code EwpgSuiteDeployed} log (filtered by assetId and token)
     */
    private Optional<AdoptedSuite> adoptExistingSuite(Web3j web3j, EvmSigner signer, String factoryAddress,
                                                      String salt, byte[] assetIdBytes, UUID assetId,
                                                      UUID deploymentId, String knownTxHash) {
        Erc3643Suite suite;
        try {
            suite = resolveDeployedSuite(web3j, factoryAddress, salt, deploymentId);
        } catch (SuiteNotDeployedException notYet) {
            return Optional.empty();
        }
        String token = suite.getTokenAddress();
        byte[] onChainAssetId = (byte[]) singleValue(web3j, token,
                new Function("assetId", List.of(), List.of(new TypeReference<Bytes32>() {})));
        Object isAgent = singleValue(web3j, token, new Function("isAgent",
                List.of(new Address(signer.address())), List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {})));
        String owner = (String) singleValue(web3j, token,
                new Function("owner", List.of(), List.of(new TypeReference<Address>() {})));
        String innerFactory = (String) singleValue(web3j, factoryAddress,
                new Function("trexFactory", List.of(), List.of(new TypeReference<Address>() {})));
        boolean ownedByUs = signer.address().equalsIgnoreCase(owner) || innerFactory.equalsIgnoreCase(owner);
        if (!Arrays.equals(onChainAssetId, assetIdBytes) || !Boolean.TRUE.equals(isAgent) || !ownedByUs) {
            throw new IllegalStateException("A T-REX suite already exists under salt " + salt + " (token "
                    + token + ") but it is not ours (assetId=" + Numeric.toHexString(onChainAssetId)
                    + ", signer agent=" + isAgent + ", owner=" + owner + ", expected signer "
                    + signer.address() + "). Asset " + assetId + " cannot be deployed on factory "
                    + factoryAddress + " until this is resolved.");
        }
        String txHash = knownTxHash != null && !knownTxHash.isBlank() ? knownTxHash
                : findSuiteCreationTx(web3j, factoryAddress, assetIdBytes, token)
                        .orElseThrow(() -> new IllegalStateException("Suite token " + token + " for asset "
                                + assetId + " exists and is ours, but its creating transaction could not be "
                                + "located via EwpgSuiteDeployed on " + factoryAddress + " (RPC log range "
                                + "limit?). Retry against an archive-capable RPC."));
        log.warn("Adopting existing ERC-3643 suite for asset={} deployment={} token={} tx={} — created by an "
                + "earlier attempt whose response was lost", assetId, deploymentId, token, txHash);
        return Optional.of(new AdoptedSuite(suite, txHash));
    }

    private Object singleValue(Web3j web3j, String contract, Function fn) {
        List<Type> out = evmContractService.call(web3j, contract, fn);
        if (out.isEmpty()) {
            throw new IllegalStateException(fn.getName() + "() returned no value at " + contract);
        }
        return out.getFirst().getValue();
    }

    private Optional<String> findSuiteCreationTx(Web3j web3j, String factoryAddress, byte[] assetIdBytes,
                                                 String tokenAddress) {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.EARLIEST,
                DefaultBlockParameterName.LATEST, factoryAddress)
                .addSingleTopic(REGISTERWERK_SUITE_DEPLOYED_TOPIC)
                .addSingleTopic(Numeric.toHexString(assetIdBytes));
        EthLog logs;
        try {
            logs = web3j.ethGetLogs(filter).send();
        } catch (java.io.IOException e) {
            log.warn("eth_getLogs failed while locating the suite creation tx for {}: {}", tokenAddress, e.getMessage());
            return Optional.empty();
        }
        if (logs.hasError() || logs.getLogs() == null) {
            return Optional.empty();
        }
        String wanted = Numeric.cleanHexPrefix(tokenAddress).toLowerCase(java.util.Locale.ROOT);
        for (EthLog.LogResult<?> result : logs.getLogs()) {
            if (result.get() instanceof Log entry && entry.getTopics() != null && entry.getTopics().size() > 2
                    && entry.getTopics().get(2).toLowerCase(java.util.Locale.ROOT).endsWith(wanted)) {
                return Optional.ofNullable(entry.getTransactionHash());
            }
        }
        return Optional.empty();
    }

    /**
     * Persists (once) the suite, accepts ownership, seeds the claim topics and audits the
     * deployment. Idempotent per deployment: an existing suite row is returned as is.
     */
    private TokenDeploymentResult finishDeployment(UUID chainConfigId, Web3j web3j, EvmSigner signer,
                                                   AdoptedSuite found) {
        UUID deploymentId = found.suite().getAssetDeploymentId();
        Optional<Erc3643Suite> recorded = suiteRepository.findByAssetDeploymentId(deploymentId);
        if (recorded.isPresent()) {
            return new TokenDeploymentResult(found.txHash(), recorded.get().getTokenAddress());
        }
        Erc3643Suite suite = found.suite();
        suite.setFactoryTxHash(found.txHash());
        Erc3643Suite saved = suiteRepository.save(suite);
        acceptSuiteOwnership(chainConfigId, web3j, signer, saved);
        seedDefaultClaimTopics(saved.getId());

        eventPublisher.publishEvent(new Erc3643SuiteDeployedEvent(
                saved.getId(), null, "SYSTEM", java.util.Map.of()));
        return new TokenDeploymentResult(found.txHash(), saved.getTokenAddress());
    }

    /**
     * {@code TREXFactory.deployTREXSuite} only makes the registry wallet the <i>pending</i> owner of
     * the suite's token, IdentityRegistry, TrustedIssuersRegistry, ClaimTopicsRegistry and
     * ModularCompliance (T-REX {@code OwnableOnceNext2StepUpgradeable}); until each is accepted,
     * its {@code onlyOwner} functions revert — e.g. {@code addModule} and every
     * {@code EwpgComplianceModule} setter, {@code addTrustedIssuer}/{@code removeTrustedIssuer},
     * {@code addClaimTopic}, token/IR {@code addAgent}. (The IdentityRegistryStorage is not
     * transferred by the factory at all.) Best effort per contract: the suite already exists
     * on-chain at this point, so a failure here must not fail the deployment —
     * {@code Erc3643LifecycleService} accepts a still-pending ownership before its first
     * owner-only call on that contract.
     */
    void acceptSuiteOwnership(UUID chainConfigId, Web3j web3j, EvmSigner signer, Erc3643Suite suite) {
        Map<String, String> contracts = new LinkedHashMap<>();
        contracts.put("token", suite.getTokenAddress());
        contracts.put("identityRegistry", suite.getIdentityRegistryAddress());
        contracts.put("trustedIssuersRegistry", suite.getTrustedIssuersRegistry());
        contracts.put("claimTopicsRegistry", suite.getClaimTopicsRegistry());
        contracts.put("compliance", suite.getComplianceAddress());
        contracts.forEach((role, address) -> {
            try {
                evmContractService.send(chainConfigId, web3j, signer, address,
                        new Function("acceptOwnership", Collections.emptyList(), Collections.emptyList()));
            } catch (RuntimeException e) {
                log.error("Accepting ownership of {}={} for suite={} failed; it will be retried before "
                        + "the first owner-only call on it", role, address, suite.getId(), e);
            }
        });
    }

    // Confidential ERC-3643 deployment is handled by ConfidentialErc3643Service (a real,
    // Web3j-backed implementation), routed via Erc3643DeploymentPortImpl.deployConfidential —
    // not here. An earlier, permanently-unreachable duplicate of this method used to sit in
    // this class throwing "not yet supported"; it was dead code (the port never called it) and
    // its message was stale even before removal.

    /**
     * Issue a KYC claim to an investor's ONCHAINID contract.
     *
     * <p>Signs a claim off-chain and calls
     * {@code ONCHAINID.addClaim(topic, scheme, issuer, signature, data, uri)} with the chain's
     * configured ONCHAINID {@code ClaimIssuer} contract as {@code issuer} (see {@link #signClaim});
     * that contract must be a TrustedIssuer in the suite's TIR.
     *
     * <p>Submits via {@link EvmContractService#submit} and returns immediately with the tx hash —
     * it does <b>not</b> wait for a receipt. {@code Erc3643ClaimConfirmationListener} confirms it
     * once mined and FINALIZED; until then the caller's {@link OnchainClaim} row must stay
     * {@code confirmed=false} and must not count toward compliance checks.
     *
     * @param onchainIdentityId ID of the ONCHAINID record to receive the claim
     * @param claimTopic        claim topic number (e.g. {@link #CLAIM_TOPIC_KYC})
     * @param expiresAt         optional expiry written into the signed claim data; {@code null} means
     *                          none. Informational only: neither ONCHAINID's {@code ClaimIssuer
     *                          .isClaimValid}, T-REX {@code isVerified} nor {@code PermissionOracle}
     *                          reads it. Expiry reaches the chain by push revocation
     *                          ({@code orgidentity.internal.KycChainPropagationListener}).
     * @return the submitted transaction's hash
     * @throws IllegalStateException if the target identity has no deployed contract yet (still
     *         {@code 0x-PENDING-...}) — previously this silently returned, letting callers persist
     *         a claim with nothing on-chain behind it at all
     */
    public String issueKycClaim(UUID onchainIdentityId, long claimTopic, java.time.Instant expiresAt) {
        log.info("Issuing claim topic={} to identity={}", claimTopic, onchainIdentityId);

        OnchainIdentity identity = identityRepository.findById(onchainIdentityId)
                .orElseThrow(() -> new EntityNotFoundException("OnchainIdentity", onchainIdentityId));

        String identityAddress = identity.getIdentityAddress();
        if (identityAddress == null || identityAddress.startsWith("0x-PENDING")) {
            throw new IllegalStateException(
                    "issueKycClaim: identity not yet deployed for id=" + onchainIdentityId);
        }

        ChainConfig chainConfig = chainConfigRepository.findById(identity.getChainConfigId())
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig",
                        identity.getChainConfigId()));
        String claimIssuer = contractAddressConfig.requireClaimIssuer(chainConfig.getIdentifier());
        requireSignerIsClaimKey(identity.getChainConfigId(), chainConfig, claimIssuer);

        // Sign via ClaimSigningService — real ABI encoding (abi.encode(address,uint256,bytes) for
        // the claim hash, matching OnchainID's ClaimIssuer.isClaimValid exactly) — with the chain's
        // registry signer, on behalf of the ClaimIssuer contract checked above.
        ClaimSigningService.SignedClaim signed = claimSigningService.signClaim(
                identity.getChainConfigId(), claimIssuer, identityAddress, claimTopic, expiresAt);

        // ONCHAINID.addClaim(uint256 topic, uint256 scheme, address issuer,
        //                    bytes signature, bytes data, string uri)
        Function fn = new Function(
                "addClaim",
                Arrays.asList(
                        new Uint256(java.math.BigInteger.valueOf(claimTopic)),
                        new Uint256(java.math.BigInteger.ONE),                // scheme = 1 (ECDSA)
                        new Address(signed.issuerAddress()),
                        new DynamicBytes(Numeric.hexStringToByteArray(signed.claimSignature())),
                        new DynamicBytes(Numeric.hexStringToByteArray(signed.claimData())),
                        new Utf8String("")
                ),
                Collections.emptyList()
        );
        Map<String, Object> txParams = Map.of(
                "onchainIdentityId", onchainIdentityId.toString(),
                "claimTopic", String.valueOf(claimTopic));
        String txHash = evmTransactions.submit(identity.getChainConfigId(),
                identityAddress, fn, txParams);
        blockchainTransactionService.record(txHash, fn.getName(), null, null,
                parseChain(chainConfig.getIdentifier()), chainConfig.getNetworkType().name(),
                identityAddress, txParams);
        log.info("issueKycClaim: topic={} submitted on identity={} tx={}", claimTopic, identityAddress, txHash);
        return txHash;
    }

    /**
     * Signs a claim exactly as {@link #issueKycClaim} submits it: signed by the chain's registry
     * signer on behalf of the chain's ONCHAINID {@code ClaimIssuer} contract
     * ({@code registerwerk.contracts.claim-issuer.<chain>}). The issuer must be a contract —
     * {@code Identity.addClaim} calls {@code IClaimIssuer(_issuer).isClaimValid}, which reverts for
     * the signer EOA, so an EOA-issued claim can never land on chain (T2-21).
     *
     * @throws IllegalStateException if no ClaimIssuer is configured for the identity's chain
     */
    public ClaimSigningService.SignedClaim signClaim(UUID chainConfigId, String identityAddress,
                                                     long topic, java.time.Instant expiresAt) {
        ChainConfig chainConfig = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        String claimIssuer = contractAddressConfig.requireClaimIssuer(chainConfig.getIdentifier());
        return claimSigningService.signClaim(chainConfigId, claimIssuer, identityAddress, topic, expiresAt);
    }

    /**
     * Pre-flight: {@code ClaimIssuer.isClaimValid} only accepts signatures from a key with CLAIM
     * (or MANAGEMENT) purpose on the issuer. Fails before broadcasting an {@code addClaim} that
     * would revert when the configured ClaimIssuer is not managed by this chain's registry signer.
     */
    private void requireSignerIsClaimKey(UUID chainConfigId, ChainConfig chainConfig, String claimIssuer) {
        String signerAddress = evmContractService.signer(chainConfigId).address();
        byte[] keyHash = org.web3j.crypto.Hash.sha3(
                leftPad20ToBytes32(hexToBytes(signerAddress.replace("0x", "").toLowerCase())));
        Function keyHasPurpose = new Function("keyHasPurpose",
                List.of(new Bytes32(keyHash), new Uint256(CLAIM_SIGNER_PURPOSE)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
        List<Type> result = evmContractService.call(web3j, claimIssuer, keyHasPurpose);
        if (result.isEmpty() || !Boolean.TRUE.equals(result.get(0).getValue())) {
            throw new IllegalStateException("ClaimIssuer " + claimIssuer + " on chain "
                    + chainConfig.getIdentifier() + " does not hold registry signer " + signerAddress
                    + " as a CLAIM/MANAGEMENT key; claims it signs would be rejected by ONCHAINID");
        }
    }

    /** ERC-734 key purpose 3 (CLAIM signer); a MANAGEMENT key (1) also satisfies it. */
    private static final java.math.BigInteger CLAIM_SIGNER_PURPOSE = java.math.BigInteger.valueOf(3);

    /**
     * Revoke a claim (e.g. when KYC expires or an investor fails re-verification).
     *
     * <p>Removal alone is reversible (see {@link #revokeClaimAtIssuer}); callers pair it with the
     * issuer-level revocation. Calls {@code ONCHAINID.removeClaim(claimId)} via Web3j — same "submit, don't wait for a
     * receipt" shape as {@link #issueKycClaim}. The caller must not set {@code revokedAt} until
     * {@code Erc3643ClaimConfirmationListener} confirms the returned tx.
     *
     * @param onchainClaimId ID of the {@code OnchainClaim} record to revoke
     * @return the submitted transaction's hash
     * @throws IllegalStateException if the target identity has no deployed contract yet
     */
    public String revokeKycClaim(UUID onchainClaimId) {
        log.info("Revoking claim={}", onchainClaimId);

        OnchainClaim claim = claimRepository.findById(onchainClaimId)
                .orElseThrow(() -> new EntityNotFoundException("OnchainClaim", onchainClaimId));
        OnchainIdentity identity = identityRepository.findById(claim.getOnchainIdentityId())
                .orElseThrow(() -> new EntityNotFoundException("OnchainIdentity",
                        claim.getOnchainIdentityId()));

        String identityAddress = identity.getIdentityAddress();
        if (identityAddress == null || identityAddress.startsWith("0x-PENDING")) {
            throw new IllegalStateException(
                    "revokeKycClaim: identity not yet deployed for claim=" + onchainClaimId);
        }

        // ERC-735 claimId = keccak256(abi.encode(issuer, topic)) — the issuer the claim was added
        // under (the ClaimIssuer contract); the signer is only a fallback for legacy rows without one.
        String issuer = claim.getIssuerAddress();
        if (issuer == null || !org.web3j.crypto.WalletUtils.isValidAddress(issuer)) {
            issuer = evmContractService.signer(identity.getChainConfigId()).address();
        }
        byte[] claimId = org.web3j.crypto.Hash.sha3(encodeClaimId(issuer, claim.getTopic()));

        ChainConfig chainConfig = chainConfigRepository.findById(identity.getChainConfigId())
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig",
                        identity.getChainConfigId()));
        // ONCHAINID.removeClaim(bytes32 claimId)
        Function fn = new Function(
                "removeClaim",
                List.of(new Bytes32(claimId)),
                Collections.emptyList()
        );
        Map<String, Object> txParams = Map.of("onchainClaimId", onchainClaimId.toString());
        String txHash = evmTransactions.submit(identity.getChainConfigId(),
                identityAddress, fn, txParams);
        blockchainTransactionService.record(txHash, fn.getName(), null, null,
                parseChain(chainConfig.getIdentifier()), chainConfig.getNetworkType().name(),
                identityAddress, txParams);
        log.info("revokeKycClaim: claim={} removal submitted on identity={} tx={}",
                onchainClaimId, identityAddress, txHash);
        return txHash;
    }

    /**
     * Revokes a claim at the issuer level: {@code ClaimIssuer.revokeClaimBySignature(signature)}.
     *
     * <p>{@link #revokeKycClaim} ({@code ONCHAINID.removeClaim}) alone is reversible: the issuer
     * still vouches for the signature, so any CLAIM key on the subject identity (an org
     * MANAGEMENT key can add itself as one) can re-{@code addClaim} the original signature and data
     * from the public {@code ClaimAdded} log. Once the issuer has revoked the signature,
     * {@code ClaimIssuer.isClaimValid} returns {@code false}, so {@code Identity.addClaim}, T-REX
     * {@code IdentityRegistry.isVerified} and {@code PermissionOracle} all reject it.
     *
     * <p>Idempotent: {@code isClaimRevoked(signature)} is read first because the contract reverts
     * {@code ClaimAlreadyRevoked} on a second revocation. Returns {@code null} (nothing submitted)
     * when there is nothing to revoke at the issuer level:
     * <ul>
     *   <li>the claim carries no signature, or no real issuer address;</li>
     *   <li>the issuer has no contract code (legacy rows signed with the chain signer EOA as
     *       issuer, before T2-21): ONCHAINID's {@code addClaim} calls {@code isClaimValid} on the
     *       issuer, which reverts for an EOA, so no such claim can exist on (or be re-added to) an
     *       identity;</li>
     *   <li>the issuer already reports the signature as revoked.</li>
     * </ul>
     * The registry signer must hold a MANAGEMENT key on the ClaimIssuer; otherwise the submitted
     * transaction reverts ({@code onlyManager}). Same "submit, don't wait" shape as
     * {@link #revokeKycClaim}.
     *
     * @param onchainClaimId ID of the {@code OnchainClaim} record to revoke at the issuer
     * @return the submitted transaction's hash, or {@code null} if nothing was submitted
     */
    public String revokeClaimAtIssuer(UUID onchainClaimId) {
        OnchainClaim claim = claimRepository.findById(onchainClaimId)
                .orElseThrow(() -> new EntityNotFoundException("OnchainClaim", onchainClaimId));
        String signature = claim.getClaimSignature();
        String issuer = claim.getIssuerAddress();
        if (signature == null || signature.isBlank() || issuer == null
                || !org.web3j.crypto.WalletUtils.isValidAddress(issuer)) {
            log.warn("revokeClaimAtIssuer: claim={} has no signature or real issuer address ({}); "
                    + "nothing to revoke at issuer level", onchainClaimId, issuer);
            return null;
        }
        OnchainIdentity identity = identityRepository.findById(claim.getOnchainIdentityId())
                .orElseThrow(() -> new EntityNotFoundException("OnchainIdentity",
                        claim.getOnchainIdentityId()));
        ChainConfig chainConfig = chainConfigRepository.findById(identity.getChainConfigId())
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig",
                        identity.getChainConfigId()));
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());

        String code;
        try {
            code = web3j.ethGetCode(issuer,
                    org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().getCode();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("revokeClaimAtIssuer: eth_getCode failed for issuer " + issuer, e);
        }
        if (code == null || code.isBlank() || "0x".equalsIgnoreCase(code)) {
            log.info("revokeClaimAtIssuer: claim={} issuer {} is an EOA, not a ClaimIssuer contract; "
                    + "ONCHAINID cannot hold a claim from it, nothing to revoke at issuer level",
                    onchainClaimId, issuer);
            return null;
        }

        byte[] sigBytes = Numeric.hexStringToByteArray(signature);
        Function isRevoked = new Function("isClaimRevoked",
                List.of(new DynamicBytes(sigBytes)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        List<Type> revoked = evmContractService.call(web3j, issuer, isRevoked);
        if (!revoked.isEmpty() && Boolean.TRUE.equals(revoked.get(0).getValue())) {
            log.info("revokeClaimAtIssuer: claim={} signature already revoked at issuer {}",
                    onchainClaimId, issuer);
            return null;
        }

        // ClaimIssuer.revokeClaimBySignature(bytes signature)
        Function fn = new Function("revokeClaimBySignature",
                List.of(new DynamicBytes(sigBytes)), Collections.emptyList());
        Map<String, Object> txParams = Map.of("onchainClaimId", onchainClaimId.toString(),
                "issuer", issuer);
        String txHash = evmTransactions.submit(identity.getChainConfigId(), issuer, fn, txParams);
        blockchainTransactionService.record(txHash, fn.getName(), null, null,
                parseChain(chainConfig.getIdentifier()), chainConfig.getNetworkType().name(),
                issuer, txParams);
        log.info("revokeClaimAtIssuer: claim={} revokeClaimBySignature submitted on issuer={} tx={}",
                onchainClaimId, issuer, txHash);
        return txHash;
    }

    /** Splits a {@code ChainConfig.identifier} like {@code "ETHEREUM_MAINNET"} into its chain
     *  segment ({@code "ETHEREUM"}), matching {@link BlockchainTransactionService#record}'s
     *  {@code chain + "_" + network} identifier-reconstruction — same helper as
     *  {@code OnChainIdService#parseChain}, duplicated rather than shared since both are private
     *  to their own package-private service. */
    private String parseChain(String identifier) {
        int splitIndex = identifier.lastIndexOf('_');
        return splitIndex > 0 ? identifier.substring(0, splitIndex) : identifier;
    }

    // ── Suite deployment helpers ──────────────────────────────────────────────

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    /** Decimals of a new T-REX token: the register counts whole units (see {@code RegisterUnits}). */
    static final int TOKEN_DECIMALS = de.makibytes.registerwerk.deployment.api.RegisterUnits.WHOLE_UNIT_DECIMALS;

    /**
     * Builds the {@code deployEwpgSuite(bytes32, string, TokenDetails, ClaimDetails)} call.
     *
     * TokenDetails: (address owner, string name, string symbol, uint8 decimals,
     *   address irs, address ONCHAINID, address[] irAgents, address[] tokenAgents,
     *   address[] complianceModules, bytes[] complianceSettings)
     * ClaimDetails: (uint256[] claimTopics, address[] issuers, uint256[][] issuerClaims)
     *
     * <p>{@code issuers}/{@code issuerClaims} must be the same length — {@code TREXFactory
     * .deployTREXSuite} reverts with {@code InvalidClaimPattern()} otherwise. There is exactly
     * one issuer (the chain's ONCHAINID ClaimIssuer contract, managed by the registry wallet),
     * trusted for every topic in {@code claimTopics} (KYC + AML).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Function buildDeployEwpgSuiteFunction(
            byte[] assetIdBytes, String salt, String ownerAddress, String claimIssuer,
            String name, String symbol) {

        DynamicStruct tokenDetails = new DynamicStruct(
                new Address(ownerAddress),
                new Utf8String(name),
                new Utf8String(symbol),
                new Uint8(java.math.BigInteger.valueOf(TOKEN_DECIMALS)), // C5: whole-unit register token
                new Address(ZERO_ADDRESS),                               // IRS: zero = deploy new
                new Address(ZERO_ADDRESS),                               // ONCHAINID: zero = deploy new
                new DynamicArray<>(Address.class, new Address(ownerAddress)), // irAgents
                new DynamicArray<>(Address.class, new Address(ownerAddress)), // tokenAgents
                new DynamicArray<>(Address.class),                       // complianceModules (empty)
                new DynamicArray<>(DynamicBytes.class)                   // complianceSettings (empty)
        );

        // Required topics: KYC + AML (AML revocation must also be visible to the on-chain
        // compliance gate). The registry's ClaimIssuer contract is the sole trusted issuer for both
        // at deploy time; more can be added later via addTrustedIssuer.
        List<Uint256> requiredTopics = List.of(
                new Uint256(java.math.BigInteger.valueOf(CLAIM_TOPIC_KYC)),
                new Uint256(java.math.BigInteger.valueOf(CLAIM_TOPIC_AML)));

        DynamicStruct claimDetails = new DynamicStruct(
                new DynamicArray<>(Uint256.class, requiredTopics),
                new DynamicArray<>(Address.class, new Address(claimIssuer)),
                new DynamicArray<>(DynamicArray.class,
                        new DynamicArray<>(Uint256.class, requiredTopics))   // issuerClaims[0] = same topics
        );

        return new Function(
                "deployEwpgSuite",
                Arrays.asList(
                        new Bytes32(assetIdBytes),
                        new Utf8String(salt),
                        tokenDetails,
                        claimDetails
                ),
                Collections.emptyList()
        );
    }

    private static String deriveSymbol(String assetName) {
        return de.makibytes.registerwerk.blockchain.api.EvmUtils.tokenSymbol(assetName);
    }

    /**
     * Resolves all six deployed T-REX suite contract addresses via {@code EwpgTREXFactory
     * .getSuiteAddresses(salt)} — a single {@code eth_call} — rather than parsing the
     * {@code EwpgSuiteDeployed} event log, which previously only carried three of the six
     * addresses (token, identityRegistry, compliance) and left {@code identityRegistryStorage}/
     * {@code claimTopicsRegistry}/{@code trustedIssuersRegistry} permanently null, silently
     * breaking every trusted-issuer/claim-topic management call against those fields (finding
     * #6). Requires the deploying tx to already be mined and successful (callers only reach
     * this after {@code EvmContractService.send()} returns, which itself throws on revert).
     *
     * @throws SuiteNotDeployedException if the factory reports no token deployed for this salt
     */
    private Erc3643Suite resolveDeployedSuite(
            Web3j web3j, String factoryAddress, String salt, UUID deploymentId) {
        Function getSuiteAddresses = new Function(
                "getSuiteAddresses",
                List.of(new Utf8String(salt)),
                Arrays.asList(
                        new TypeReference<Address>() {}, new TypeReference<Address>() {},
                        new TypeReference<Address>() {}, new TypeReference<Address>() {},
                        new TypeReference<Address>() {}, new TypeReference<Address>() {})
        );
        List<Type> result = evmContractService.call(web3j, factoryAddress, getSuiteAddresses);

        String tokenAddress = (String) result.get(0).getValue();
        if (ZERO_ADDRESS.equalsIgnoreCase(tokenAddress)) {
            throw new SuiteNotDeployedException(
                    "EwpgTREXFactory.getSuiteAddresses(" + salt + ") returned no token — "
                            + "suite was not actually deployed under this salt");
        }

        Erc3643Suite suite = new Erc3643Suite();
        suite.setAssetDeploymentId(deploymentId);
        suite.setIsConfidential(false);
        suite.setTokenAddress(tokenAddress);
        suite.setIdentityRegistryAddress((String) result.get(1).getValue());
        suite.setIdentityRegistryStorage((String) result.get(2).getValue());
        suite.setTrustedIssuersRegistry((String) result.get(3).getValue());
        suite.setClaimTopicsRegistry((String) result.get(4).getValue());
        suite.setComplianceAddress((String) result.get(5).getValue());
        return suite;
    }

    /** No suite exists under the salt (yet). */
    static final class SuiteNotDeployedException extends IllegalStateException {
        SuiteNotDeployedException(String message) {
            super(message);
        }
    }

    /**
     * Seeds the DB-side required-claim-topics record (KYC + AML) for a freshly deployed suite,
     * mirroring the on-chain {@code ClaimTopicsRegistry} state set by {@link
     * #buildDeployEwpgSuiteFunction}. Without this, {@code IdentityRegistryService.isVerified}'s
     * suite-scoped topic lookup would find zero required topics for every new suite and
     * vacuously report every investor as verified.
     */
    private void seedDefaultClaimTopics(UUID suiteId) {
        Erc3643ClaimTopic kyc = new Erc3643ClaimTopic();
        kyc.setSuiteId(suiteId);
        kyc.setTopic(CLAIM_TOPIC_KYC);
        kyc.setLabel("KYC");
        claimTopicRepository.save(kyc);

        Erc3643ClaimTopic aml = new Erc3643ClaimTopic();
        aml.setSuiteId(suiteId);
        aml.setTopic(CLAIM_TOPIC_AML);
        aml.setLabel("AML");
        claimTopicRepository.save(aml);
    }

    // ── Signing helpers ───────────────────────────────────────────────────────
    // Claim signing itself goes through ClaimSigningService (see issueKycClaim), matching
    // OnchainID's real abi.encode(address,uint256,bytes) layout. encodeClaimId below encodes
    // only static types (address, uint256), so a packed concat is correct here with no
    // dynamic-type offset/length words needed.

    private static byte[] encodeClaimId(String issuer, long topic) {
        // keccak256(abi.encode(issuer_as_address, topic_as_uint256))
        byte[] packed = concat(
                leftPad20ToBytes32(hexToBytes(issuer.replace("0x", "").toLowerCase())),
                toBigEndian32(java.math.BigInteger.valueOf(topic))
        );
        return packed;
    }

    private static byte[] leftPad20ToBytes32(byte[] addr) {
        byte[] b = new byte[32];
        System.arraycopy(addr, 0, b, 12, Math.min(addr.length, 20));
        return b;
    }

    private static byte[] toBigEndian32(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] padded = new byte[32];
        int srcPos = Math.max(0, raw.length - 32);
        System.arraycopy(raw, srcPos, padded, 32 - (raw.length - srcPos), raw.length - srcPos);
        return padded;
    }

    private static byte[] hexToBytes(String hex) {
        return Numeric.hexStringToByteArray("0x" + hex);
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] result = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }
}
