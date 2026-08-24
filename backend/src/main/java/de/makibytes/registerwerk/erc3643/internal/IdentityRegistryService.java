package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopic;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.events.InvestorRegisteredEvent;
import de.makibytes.registerwerk.erc3643.events.InvestorRemovedEvent;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint16;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the wallet-address → ONCHAINID mapping for ERC-3643 tokens.
 *
 * <p>Off-chain mirror of the on-chain {@code IIdentityRegistry} contract.
 * The canonical state is on-chain; this table allows the backend to answer
 * identity questions without an RPC call on every request.</p>
 *
 */
@Service
public class IdentityRegistryService {

    private final Erc3643IdentityRegistryRepository registryRepo;
    private final Erc3643SuiteRepository suiteRepo;
    private final Erc3643ClaimTopicRepository claimTopicRepo;
    private final AssetDeploymentRepository deploymentRepo;
    private final OnChainIdService onChainIdService;
    private final DurableEvmTransactionGateway evmTransactions;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ApplicationEventPublisher eventPublisher;

    public IdentityRegistryService(Erc3643IdentityRegistryRepository registryRepo,
                                   Erc3643SuiteRepository suiteRepo,
                                    Erc3643ClaimTopicRepository claimTopicRepo,
                                    AssetDeploymentRepository deploymentRepo,
                                    OnChainIdService onChainIdService,
                                    DurableEvmTransactionGateway evmTransactions,
                                    BlockchainTransactionService blockchainTransactionService,
                                    ApplicationEventPublisher eventPublisher) {
        this.registryRepo = registryRepo;
        this.suiteRepo = suiteRepo;
        this.claimTopicRepo = claimTopicRepo;
        this.deploymentRepo = deploymentRepo;
        this.onChainIdService = onChainIdService;
        this.evmTransactions = evmTransactions;
        this.blockchainTransactionService = blockchainTransactionService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Registers an investor wallet in the on-chain IdentityRegistry and persists the mapping.
     *
     * <p>If the investor's ONCHAINID has not been deployed yet on the target chain, it is
     * created first via {@link OnChainIdService#getOrCreate}.</p>
     *
     * @param suiteId        T-REX suite to register the investor in
     * @param walletAddress  investor's on-chain wallet address (checksummed or lowercase)
     * @param legalEntityId  investor's legal entity UUID
     * @param chainConfigId  chain_config row ID (determines which chain ONCHAINID is deployed on)
     * @param countryCode    ISO-3166-1 numeric country code; null if unknown
     * @param actorId        ID of the user registering the investor (for audit)
     * @param actorRole      role of the user registering the investor (for audit)
     * @return persisted registry entry
     */
    @Transactional
    public Erc3643IdentityRegistry registerInvestor(UUID suiteId,
                                                     String walletAddress,
                                                     UUID legalEntityId,
                                                     UUID chainConfigId,
                                                     Short countryCode,
                                                     UUID actorId,
                                                     String actorRole) {
        suiteRepo.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        // Ensure ONCHAINID exists for this entity on the target chain
        OnchainIdentity identity = onChainIdService.getOrCreate(legalEntityId, chainConfigId, actorId, actorRole);

        // IIdentityRegistry.registerIdentity(address _userAddress, address _identity, uint16 _country)
        Erc3643Suite suite = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        if (suite.getIdentityRegistryAddress() != null
                && !suite.getIdentityRegistryAddress().startsWith("0x-PENDING")) {
            try {
                AssetDeployment deployment = deploymentRepo.findById(suite.getAssetDeploymentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "AssetDeployment not found for suite " + suite.getId()));
                String identityAddr = identity.getIdentityAddress() != null
                        ? identity.getIdentityAddress() : "0x0000000000000000000000000000000000000000";
                short country = countryCode != null ? countryCode : 0;

                Function fn = new Function(
                        "registerIdentity",
                        java.util.Arrays.asList(
                                new Address(walletAddress),
                                new Address(identityAddr),
                                new Uint16(java.math.BigInteger.valueOf(country))
                        ),
                        java.util.Collections.emptyList()
                );
                String txHash = evmTransactions.submit(chainConfigId,
                        suite.getIdentityRegistryAddress(), fn,
                        java.util.Map.of(
                                "walletAddress", walletAddress,
                                "legalEntityId", legalEntityId.toString()));
                blockchainTransactionService.record(
                        txHash,
                        fn.getName(),
                        deployment.getId(),
                        deployment.getAssetId(),
                        deployment.getChain().name(),
                        deployment.getNetwork().name(),
                        suite.getIdentityRegistryAddress(),
                        java.util.Map.of(
                                "walletAddress", walletAddress,
                                "legalEntityId", legalEntityId.toString()
                        )
                );

                Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
                entry.setSuiteId(suiteId);
                entry.setWalletAddress(walletAddress.toLowerCase());
                entry.setOnchainIdentityId(identity.getId());
                entry.setCountryCode(countryCode);
                entry.setRegisteredByTx(txHash);
                entry.setChainConfigId(chainConfigId);
                Erc3643IdentityRegistry saved = registryRepo.save(entry);
                eventPublisher.publishEvent(new InvestorRegisteredEvent(suiteId, actorId, actorRole,
                        java.util.Map.of("walletAddress", walletAddress, "legalEntityId", legalEntityId.toString())));
                return saved;
            } catch (Exception e) {
                throw new RuntimeException("registerIdentity submission failed: " + e.getMessage(), e);
            }
        }

        // Identity registry not yet deployed — persist DB record optimistically
        Erc3643IdentityRegistry entry = new Erc3643IdentityRegistry();
        entry.setSuiteId(suiteId);
        entry.setWalletAddress(walletAddress.toLowerCase());
        entry.setOnchainIdentityId(identity.getId());
        entry.setCountryCode(countryCode);
        entry.setChainConfigId(chainConfigId);
        Erc3643IdentityRegistry saved = registryRepo.save(entry);
        eventPublisher.publishEvent(new InvestorRegisteredEvent(suiteId, actorId, actorRole,
                java.util.Map.of("walletAddress", walletAddress, "legalEntityId", legalEntityId.toString())));
        return saved;
    }

    /**
     * Removes an investor from the on-chain IdentityRegistry and soft-deletes the DB entry.
     *
     * <p>Submits {@code deleteIdentity} non-blocking and tracks it via
     * {@link BlockchainTransactionService#record} — mirroring {@link #registerInvestor}'s
     * pattern exactly, rather than the {@code send()}/{@code waitForReceipt()} blocking call
     * this used before. That older version accepted the transaction as final the moment it was
     * first mined, with no reorg guard and no async re-verification of any kind: a reorg
     * un-mining {@code deleteIdentity} would leave the register asserting {@code removedAt} set
     * while the on-chain IdentityRegistry might still list the investor — a genuine compliance
     * gap, since {@code removedAt IS NULL} is how {@link Erc3643IdentityRegistryRepository}
     * decides whether a wallet is still whitelisted. The soft-delete write here remains
     * optimistic (matching {@link #registerInvestor}'s already-accepted risk profile for the
     * mirror-image operation) but is now tracked through the same model-aware
     * {@code BlockchainTransactionService.pollPendingTransactions} every other correction in
     * this registry uses, instead of being invisible to it entirely.
     *
     * @param actorId   ID of the user removing the investor (for audit)
     * @param actorRole role of the user removing the investor (for audit)
     */
    @Transactional
    public void removeInvestor(UUID suiteId, UUID registryEntryId, UUID actorId, String actorRole) {
        Erc3643IdentityRegistry entry = registryRepo.findById(registryEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Registry entry not found: " + registryEntryId));

        if (!entry.getSuiteId().equals(suiteId)) {
            throw new IllegalArgumentException("Registry entry does not belong to suite " + suiteId);
        }

        // IIdentityRegistry.deleteIdentity(address _userAddress)
        Erc3643Suite suite = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        if (suite.getIdentityRegistryAddress() != null
                && !suite.getIdentityRegistryAddress().startsWith("0x-PENDING")) {
            try {
                AssetDeployment deployment = deploymentRepo.findById(suite.getAssetDeploymentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "AssetDeployment not found for suite " + suite.getId()));
                Function fn = new Function(
                        "deleteIdentity",
                        java.util.List.of(new Address(entry.getWalletAddress())),
                        java.util.Collections.emptyList()
                );
                if (entry.getChainConfigId() == null) {
                    throw new IllegalStateException(
                            "Identity registry entry is missing chainConfigId: " + entry.getId());
                }
                String txHash = evmTransactions.submit(entry.getChainConfigId(),
                        suite.getIdentityRegistryAddress(), fn,
                        java.util.Map.of("walletAddress", entry.getWalletAddress()));
                blockchainTransactionService.record(
                        txHash,
                        fn.getName(),
                        deployment.getId(),
                        deployment.getAssetId(),
                        deployment.getChain().name(),
                        deployment.getNetwork().name(),
                        suite.getIdentityRegistryAddress(),
                        java.util.Map.of("walletAddress", entry.getWalletAddress())
                );
                entry.setRemovedByTx(txHash);
                // Reset in case this entry was previously removed, reorg-reverted back to active by
                // IdentityRegistryRemovalRevertCompensator, and is now being removed again — without this,
                // removalConfirmed would still be true from the first removal and
                // Erc3643IdentityRegistryConfirmationListener would never re-poll this second attempt.
                entry.setRemovalConfirmed(false);
            } catch (Exception e) {
                throw new RuntimeException("deleteIdentity submission failed: " + e.getMessage(), e);
            }
        }

        entry.setRemovedAt(Instant.now());
        registryRepo.save(entry);
        eventPublisher.publishEvent(new InvestorRemovedEvent(suiteId, actorId, actorRole,
                java.util.Map.of("registryEntryId", registryEntryId.toString(), "walletAddress", entry.getWalletAddress())));
    }

    /** All currently active (non-removed) registry entries for a suite. */
    public List<Erc3643IdentityRegistry> getRegisteredInvestors(UUID suiteId) {
        return registryRepo.findBySuiteIdAndRemovedAtIsNull(suiteId);
    }

    /** All entries including removed — for audit purposes. */
    public List<Erc3643IdentityRegistry> getAllEntries(UUID suiteId) {
        return registryRepo.findBySuiteId(suiteId);
    }

    /** Whether a wallet is currently registered in the IdentityRegistry for a suite. */
    public boolean isRegistered(UUID suiteId, String walletAddress) {
        return registryRepo.existsBySuiteIdAndWalletAddressAndRemovedAtIsNull(
                suiteId, walletAddress.toLowerCase());
    }

    /**
     * Whether a wallet is verified: registered AND holds all of this suite's required claims
     * (its {@code Erc3643ClaimTopic} rows) — checking against the suite's actual requirements
     * rather than just KYC means a revoked AML/Accreditation claim is caught too. Uses the local
     * DB mirror; for an authoritative result use an on-chain
     * {@code IIdentityRegistry.isVerified(walletAddress)} call.
     */
    public boolean isVerified(UUID suiteId, String walletAddress) {
        Optional<Erc3643IdentityRegistry> entry = registryRepo
                .findBySuiteIdAndWalletAddressAndRemovedAtIsNull(suiteId, walletAddress.toLowerCase());
        if (entry.isEmpty()) return false;

        OnchainIdentity identity = onChainIdService
                .findIdentityById(entry.get().getOnchainIdentityId()).orElse(null);
        if (identity == null) return false;

        List<Long> requiredTopics = claimTopicRepo.findBySuiteId(suiteId).stream()
                .map(Erc3643ClaimTopic::getTopic)
                .toList();
        return onChainIdService.isVerified(identity.getLegalEntityId(), identity.getChainConfigId(), requiredTopics);
    }
}
