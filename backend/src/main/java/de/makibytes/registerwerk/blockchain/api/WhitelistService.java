package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages on-chain and off-chain whitelisting of investor wallet addresses.
 *
 * <p>On EVM chains the token contract inherits {@code EwpgCompliance}, which exposes:
 * <ul>
 *   <li>{@code whitelist(address account)} — adds {@code account} to the transfer whitelist</li>
 *   <li>{@code removeFromWhitelist(address account)} — removes it</li>
 * </ul>
 * Both functions are {@code onlyRegistry} (only the registry wallet may call them).
 *
 * <p>The on-chain call is fire-and-track: {@link EvmContractService#submit} returns the tx hash
 * immediately and {@link BlockchainTransactionService} polls for the receipt asynchronously,
 * unlike the blocking {@link EvmContractService#send} (which would wait up to two minutes for a
 * receipt synchronously inside this {@code @Transactional} HTTP request handler). The off-chain
 * DB record is updated optimistically regardless of on-chain confirmation status.
 */
@Service
@Transactional
public class WhitelistService {

    private static final Logger log = LoggerFactory.getLogger(WhitelistService.class);

    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;

    public WhitelistService(
            AssetDeploymentRepository assetDeploymentRepository,
            AssetHolderRepository assetHolderRepository,
            BlockchainClientRegistry blockchainClientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService) {
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
    }

    /**
     * Whitelists {@code walletAddress} on-chain and marks it as whitelisted in the database.
     *
     * <p>Calls {@code EwpgCompliance.whitelist(walletAddress)} on the token contract at
     * {@code deployment.contractAddress}.
     *
     * @param deploymentId  ID of the asset deployment
     * @param walletAddress EVM address to whitelist
     */
    public void whitelist(UUID deploymentId, String walletAddress) {
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));

        log.info("Whitelisting address={} on deployment={}", walletAddress, deploymentId);

        if (deployment.getContractAddress() != null && !deployment.getContractAddress().isBlank()
                && !deployment.getContractAddress().startsWith("0x-PENDING")) {
            callWhitelistFunction(deployment, walletAddress, "whitelist");
        } else {
            log.warn("Contract not yet deployed for deployment={}; skipping on-chain whitelist call.",
                    deploymentId);
        }

        // Active-only: a removed holder's row must not be silently re-flagged as whitelisted.
        assetHolderRepository.findActiveByAssetIdAndWalletAddress(deployment.getAssetId(), EvmUtils.normalizeAddress(walletAddress))
                .ifPresent(holder -> {
                    holder.setWhitelisted(true);
                    assetHolderRepository.save(holder);
                });
    }

    /**
     * Removes {@code walletAddress} from the on-chain whitelist and updates the DB record.
     *
     * <p>Calls {@code EwpgCompliance.removeFromWhitelist(walletAddress)} on the token contract.
     *
     * @param deploymentId  ID of the asset deployment
     * @param walletAddress address to remove from the whitelist
     */
    public void removeFromWhitelist(UUID deploymentId, String walletAddress) {
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));

        log.info("Removing address={} from whitelist on deployment={}", walletAddress, deploymentId);

        if (deployment.getContractAddress() != null && !deployment.getContractAddress().isBlank()
                && !deployment.getContractAddress().startsWith("0x-PENDING")) {
            callWhitelistFunction(deployment, walletAddress, "removeFromWhitelist");
        } else {
            log.warn("Contract not yet deployed for deployment={}; skipping on-chain whitelist call.",
                    deploymentId);
        }

        // Active-only: a removed holder's row must not be silently re-flagged.
        assetHolderRepository.findActiveByAssetIdAndWalletAddress(deployment.getAssetId(), EvmUtils.normalizeAddress(walletAddress))
                .ifPresent(holder -> {
                    holder.setWhitelisted(false);
                    assetHolderRepository.save(holder);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void callWhitelistFunction(AssetDeployment deployment, String walletAddress,
                                       String functionName) {
        try {
            ChainDescriptor descriptor = new ChainDescriptor(deployment.getChain(), deployment.getNetwork());
            Web3j web3j = blockchainClientRegistry.getEvmClient(descriptor);
            Credentials creds = evmContractService.credentials(descriptor);

            Function fn = new Function(
                    functionName,
                    List.of(new Address(walletAddress)),
                    Collections.emptyList()
            );

            // Fire-and-track: submit() returns the tx hash immediately;
            // BlockchainTransactionService polls for the receipt asynchronously, instead of this
            // call blocking the HTTP thread for up to two minutes like send() did.
            String txHash = evmContractService.submit(web3j, creds, deployment.getContractAddress(), fn);
            txService.record(txHash, functionName, deployment.getId(), deployment.getAssetId(),
                    deployment.getChain().name(), deployment.getNetwork().name(),
                    deployment.getContractAddress(), Map.of("walletAddress", walletAddress));
            log.info("{}({}) submitted on contract={} txHash={}", functionName, walletAddress,
                    deployment.getContractAddress(), txHash);
        } catch (Exception e) {
            throw new RuntimeException(
                    "On-chain " + functionName + " failed for wallet=" + walletAddress
                    + " on deployment=" + deployment.getId() + ": " + e.getMessage(), e);
        }
    }
}
