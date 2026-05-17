package de.makibytes.registerwerk.erc3643.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialTokenEvents;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc20DeploymentService;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;

/**
 * Deploys Confidential ERC-3643 security tokens (Zama fhEVM + T-REX) via
 * {@code EwpgConfidentialFactory.deployConfidentialErc3643(bytes32, string, string, address, address)}.
 *
 * <p>Confidential ERC-3643 combines T-REX identity/compliance enforcement with
 * ERC-7984 encrypted balances. The target chain must:
 * <ul>
 *   <li>Be an fhEVM-capable network (Fhenix or Inco)</li>
 *   <li>Have an EwpgConfidentialFactory deployed and configured with the Zama KMS Gateway</li>
 *   <li>Have a T-REX IdentityRegistry and a compliance module for the asset</li>
 * </ul>
 */
@Service
public class ConfidentialErc3643Service {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialErc3643Service.class);

    /** Placeholder IdentityRegistry/compliance when not yet provisioned — operator must update later. */
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetRepository assetRepository;

    public ConfidentialErc3643Service(BlockchainClientRegistry blockchainClientRegistry,
                                      EvmContractService evmContractService,
                                      ContractAddressConfig contractAddressConfig,
                                      AssetRepository assetRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetRepository = assetRepository;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying Confidential ERC-3643: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireConfidentialFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            byte[] assetIdBytes = Erc20DeploymentService.uuidToBytes32(assetId);

            Function deploy = new Function(
                    "deployConfidentialErc3643",
                    Arrays.asList(
                            new Bytes32(assetIdBytes),
                            new Utf8String(asset.getName()),
                            new Utf8String(asset.getTokenStandard().name()),
                            new Address(ZERO_ADDRESS),
                            new Address(ZERO_ADDRESS)
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, creds, factoryAddress, deploy);

            String tokenAddress = ConfidentialTokenEvents.extractTokenAddress(receipt);
            log.info("Confidential ERC-3643 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }
}
