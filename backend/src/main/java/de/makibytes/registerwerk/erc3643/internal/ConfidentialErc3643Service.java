package de.makibytes.registerwerk.erc3643.internal;

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
 * Deploys Confidential ERC-3643 security tokens (Zama fhEVM + T-REX) via
 * {@code EwpgConfidentialFactory.deployConfidentialErc3643(bytes32, string, string, address[], address, address)}.
 *
 * <p>Confidential ERC-3643 combines T-REX identity/compliance enforcement with
 * ERC-7984 encrypted balances. The target chain must:
 * <ul>
 *   <li>Have real Zama FHEVM host contracts configured (Ethereum/Sepolia today, or T-REX Chain
 *       once it publishes its own addresses — see {@code EwpgConfidentialFactory}'s doc)</li>
 *   <li>Have an EwpgConfidentialFactory deployed and configured with those FHEVM addresses</li>
 *   <li>Have a shared T-REX IdentityRegistry provisioned for confidential assets on this chain
 *       ({@code registerwerk.contracts.confidential-identity-registry.*} — see
 *       {@link de.makibytes.registerwerk.blockchain.api.ContractAddressConfig}). Previously this
 *       was silently hardcoded to the zero address, which deploys a "regulated" security token
 *       enforcing no KYC gate at all; it now fails loudly instead if unconfigured.</li>
 * </ul>
 */
@Service
public class ConfidentialErc3643Service {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialErc3643Service.class);

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;

    public ConfidentialErc3643Service(BlockchainClientRegistry blockchainClientRegistry,
                                      EvmContractService evmContractService,
                                      ContractAddressConfig contractAddressConfig,
                                      AssetLookupPort assetLookupPort) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying Confidential ERC-3643: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireConfidentialFactory(chainId);
            String identityRegistryAddress = contractAddressConfig.requireConfidentialIdentityRegistry(chainId);
            String complianceAddress = contractAddressConfig.confidentialComplianceOrZero(chainId);

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
                // Fail closed (finding #7, Phase 9): deploying anyway would create a live register
                // entry nobody at the operator can ever decrypt — reconciliation, Travel Rule
                // screening, and regulator disclosure would all be permanently blind to it until
                // someone notices and calls addViewer, which nothing prompts them to do.
                throw new IllegalStateException(
                        "No confidential viewers configured for chain=" + chainId + " (registerwerk.contracts."
                        + "confidential-operator-viewer." + chainId + " / confidential-auditor-viewer." + chainId
                        + "). Refusing to deploy assetId=" + assetId
                        + " with no operator/auditor decrypt access — configure at least one viewer first.");
            }

            Function deploy = new Function(
                    "deployConfidentialErc3643",
                    Arrays.asList(
                            new Bytes32(assetIdBytes),
                            new Utf8String(asset.name()),
                            new Utf8String(EvmUtils.tokenSymbol(asset)),
                            new DynamicArray<>(Address.class, initialViewers),
                            new Address(identityRegistryAddress),
                            new Address(complianceAddress)
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, signer, factoryAddress, deploy);

            String tokenAddress = ConfidentialTokenEvents.extractTokenAddress(receipt);
            log.info("Confidential ERC-3643 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }
}
