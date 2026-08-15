package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.ConfidentialTokenEvents;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;

/**
 * Deploys ERC-7984 confidential fungible tokens (Zama fhEVM) via
 * {@code EwpgConfidentialFactory.deployConfidentialErc20(bytes32, string, string, address[])}.
 *
 * <p>Confidential tokens encrypt balances and transfer amounts with Fully
 * Homomorphic Encryption. The factory must be pre-deployed on a chain with real
 * Zama FHEVM host contracts configured (Ethereum/Sepolia today, or T-REX Chain
 * once it publishes its own FHEVM infrastructure addresses — NOT Fhenix or Inco,
 * which are separate, non-Zama confidential-EVM stacks with incompatible
 * libraries) and its address stored in
 * {@code registerwerk.contracts.confidential-factory.{chain-identifier}}.
 */
@Service
public class ConfidentialErc20Service {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialErc20Service.class);

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;

    public ConfidentialErc20Service(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetLookupPort assetLookupPort) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying Confidential ERC-20 (ERC-7984): assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireConfidentialFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            EvmSigner signer = evmContractService.signer(chain);

            byte[] assetIdBytes = EvmUtils.uuidToBytes32(assetId);

            // Registerwerk's own operator/auditor viewer roles (see ConfidentialERC20's
            // viewer-ACL note) — provisioned at construction so no separate post-deploy
            // addViewer transaction is needed before the first mint is reconcilable.
            List<Address> initialViewers = contractAddressConfig.confidentialInitialViewers(chainId).stream()
                    .map(Address::new)
                    .collect(Collectors.toList());
            if (initialViewers.isEmpty()) {
                log.warn("No confidential viewers configured for chain={} — deploying assetId={} with no "
                        + "operator/auditor decrypt access until addViewer is called explicitly.", chainId, assetId);
            }

            Function deploy = new Function(
                    "deployConfidentialErc20",
                    Arrays.asList(
                            new Bytes32(assetIdBytes),
                            new Utf8String(asset.name()),
                            new Utf8String(EvmUtils.tokenSymbol(asset)),
                            new DynamicArray<>(Address.class, initialViewers)
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, signer, factoryAddress, deploy);

            String tokenAddress = ConfidentialTokenEvents.extractTokenAddress(receipt);
            log.info("Confidential ERC-20 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }
}
