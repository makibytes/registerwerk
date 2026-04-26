package de.makibytes.registerwerk.application.erc3643;

import de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry;
import de.makibytes.registerwerk.application.blockchain.BlockchainTransactionService;
import de.makibytes.registerwerk.application.blockchain.ChainDescriptor;
import de.makibytes.registerwerk.application.blockchain.EvmContractService;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.erc3643.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.domain.erc3643.Erc3643Suite;
import de.makibytes.registerwerk.domain.erc3643.OnchainIdentity;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643SuiteRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

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
 * <p>On-chain writes are currently stubs ({@code // TODO: Web3j}) — the DB mirror
 * is updated optimistically so the API is functional during development.</p>
 */
@Service
public class IdentityRegistryService {

    private final Erc3643IdentityRegistryRepository registryRepo;
    private final Erc3643SuiteRepository suiteRepo;
    private final AssetDeploymentRepository deploymentRepo;
    private final OnChainIdService onChainIdService;
    private final EvmContractService evmContractService;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final BlockchainTransactionService blockchainTransactionService;

    public IdentityRegistryService(Erc3643IdentityRegistryRepository registryRepo,
                                   Erc3643SuiteRepository suiteRepo,
                                    AssetDeploymentRepository deploymentRepo,
                                    OnChainIdService onChainIdService,
                                    EvmContractService evmContractService,
                                    BlockchainClientRegistry blockchainClientRegistry,
                                    BlockchainTransactionService blockchainTransactionService) {
        this.registryRepo = registryRepo;
        this.suiteRepo = suiteRepo;
        this.deploymentRepo = deploymentRepo;
        this.onChainIdService = onChainIdService;
        this.evmContractService = evmContractService;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.blockchainTransactionService = blockchainTransactionService;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Web3j clientForSuite(Erc3643Suite suite) {
        AssetDeployment dep = deploymentRepo.findById(suite.getAssetDeploymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "AssetDeployment not found for suite " + suite.getId()));
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        return blockchainClientRegistry.getEvmClient(descriptor);
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
     * @return persisted registry entry
     */
    @Transactional
    public Erc3643IdentityRegistry registerInvestor(UUID suiteId,
                                                     String walletAddress,
                                                     UUID legalEntityId,
                                                     UUID chainConfigId,
                                                     Short countryCode) {
        suiteRepo.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        // Ensure ONCHAINID exists for this entity on the target chain
        OnchainIdentity identity = onChainIdService.getOrCreate(legalEntityId, chainConfigId);

        // IIdentityRegistry.registerIdentity(address _userAddress, address _identity, uint16 _country)
        Erc3643Suite suite = suiteRepo.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        if (suite.getIdentityRegistryAddress() != null
                && !suite.getIdentityRegistryAddress().startsWith("0x-PENDING")) {
            try {
                AssetDeployment deployment = deploymentRepo.findById(suite.getAssetDeploymentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "AssetDeployment not found for suite " + suite.getId()));
                Web3j web3j = clientForSuite(suite);
                Credentials creds = evmContractService.credentials();
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
                String txHash = evmContractService.submit(web3j, creds,
                        suite.getIdentityRegistryAddress(), fn);
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
                return registryRepo.save(entry);
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
        return registryRepo.save(entry);
    }

    /**
     * Removes an investor from the on-chain IdentityRegistry and soft-deletes the DB entry.
     */
    @Transactional
    public void removeInvestor(UUID suiteId, UUID registryEntryId) {
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
                Web3j web3j = clientForSuite(suite);
                Credentials creds = evmContractService.credentials();
                Function fn = new Function(
                        "deleteIdentity",
                        java.util.List.of(new Address(entry.getWalletAddress())),
                        java.util.Collections.emptyList()
                );
                evmContractService.send(web3j, creds, suite.getIdentityRegistryAddress(), fn);
            } catch (Exception e) {
                throw new RuntimeException("deleteIdentity on-chain call failed: " + e.getMessage(), e);
            }
        }

        entry.setRemovedAt(Instant.now());
        registryRepo.save(entry);
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
     * Whether a wallet is verified: registered AND holds all required claims.
     * Uses the local DB mirror; for an authoritative result use an on-chain
     * {@code IIdentityRegistry.isVerified(walletAddress)} call.
     */
    public boolean isVerified(UUID suiteId, String walletAddress) {
        Optional<Erc3643IdentityRegistry> entry = registryRepo
                .findBySuiteIdAndWalletAddressAndRemovedAtIsNull(suiteId, walletAddress.toLowerCase());
        if (entry.isEmpty()) return false;

        OnchainIdentity identity = onChainIdService
                .findIdentityById(entry.get().getOnchainIdentityId()).orElse(null);
        if (identity == null) return false;

        return onChainIdService.isVerified(identity.getLegalEntityId(), identity.getChainConfigId());
    }
}
