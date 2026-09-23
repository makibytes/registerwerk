package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.wallet.api.EvmSigner;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * Idempotent {@code AssetTokenFactory.deployToken}/{@code deployVault} submission shared by the
 * six EVM factory-routed deployment services (T1-17).
 *
 * <p>The factory's CREATE2 salt is {@code keccak(assetId, tokenType)} and the initcode is fully
 * determined by public calldata, so on factories deployed before the {@code onlyRegistry}
 * restriction anyone can replay the registry's calldata first and make the registry's own
 * transaction revert with {@code CREATE2 failed}. The same revert happens when a previous
 * attempt's transaction was mined but its response was lost. In both cases the contract at
 * {@code predictAddress} is byte-for-byte the one we asked for (the CREATE2 address commits to
 * the initcode, including our registry wallet and assetId), so it is adopted instead of failing:
 *
 * <ol>
 *   <li>before sending, and again after a failed send, ask the factory for
 *       {@code predictAddress(...)} and check for code there;</li>
 *   <li>if code exists, read back {@code assetId()} and {@code registry()} and require them to
 *       equal our assetId and our signing wallet — otherwise fail loudly, because the registry
 *       authority was handed over or the factory is not the one we think it is;</li>
 *   <li>locate the creating transaction through the factory's {@code TokenDeployed} /
 *       {@code VaultDeployed} log (filtered by assetId and address) so the normal receipt-based
 *       finality pipeline in {@code AssetDeploymentService.syncFromChain} confirms it.</li>
 * </ol>
 */
@Component
public class EvmFactoryDeploymentSupport {

    private static final Logger log = LoggerFactory.getLogger(EvmFactoryDeploymentSupport.class);

    static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + Hash.sha3String("TokenDeployed(bytes32,uint8,address)");
    static final String VAULT_DEPLOYED_TOPIC =
            "0x" + Hash.sha3String("VaultDeployed(bytes32,uint8,address,address)");

    private final EvmContractService evmContractService;

    public EvmFactoryDeploymentSupport(EvmContractService evmContractService) {
        this.evmContractService = evmContractService;
    }

    /**
     * Deploys (or adopts) the factory product for {@code assetId}.
     *
     * @param underlyingAsset {@code null} for {@code deployToken} (types 0–3); the ERC-20
     *                        underlying for {@code deployVault} (types 4/5)
     */
    public TokenDeploymentResult deploy(ChainDescriptor chain, Web3j web3j, EvmSigner signer,
                                        String factoryAddress, BigInteger tokenType, String name,
                                        String symbol, UUID assetId, String underlyingAsset) {
        boolean vault = underlyingAsset != null;
        String eventTopic = vault ? VAULT_DEPLOYED_TOPIC : TOKEN_DEPLOYED_TOPIC;
        byte[] assetIdBytes = EvmUtils.uuidToBytes32(assetId);

        Optional<TokenDeploymentResult> existing = adoptExisting(web3j, signer, factoryAddress,
                tokenType, name, symbol, assetIdBytes, underlyingAsset, eventTopic, assetId);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<Type> args = new ArrayList<>(List.of(new Uint8(tokenType), new Utf8String(name),
                new Utf8String(symbol), new Bytes32(assetIdBytes)));
        if (vault) {
            args.add(new Address(underlyingAsset));
        }
        Function deployCall = new Function(vault ? "deployVault" : "deployToken", args,
                List.of(new TypeReference<Address>() {}));

        TransactionReceipt receipt;
        try {
            receipt = evmContractService.send(
                    evmContractService.chainConfigId(chain), web3j, signer, factoryAddress, deployCall);
        } catch (RuntimeException sendFailure) {
            // A lost response or a front-runner may have put our contract there in the meantime.
            Optional<TokenDeploymentResult> adopted;
            try {
                adopted = adoptExisting(web3j, signer, factoryAddress, tokenType, name, symbol,
                        assetIdBytes, underlyingAsset, eventTopic, assetId);
            } catch (RuntimeException recheckFailure) {
                sendFailure.addSuppressed(recheckFailure);
                throw sendFailure;
            }
            return adopted.orElseThrow(() -> sendFailure);
        }

        String address = EvmUtils.extractIndexedAddress(receipt, eventTopic, 3)
                .orElseThrow(() -> new RuntimeException((vault ? "VaultDeployed" : "TokenDeployed")
                        + " event not found in receipt: " + receipt.getTransactionHash()));
        log.info("Factory deployment: assetId={} type={} → address={} tx={}",
                assetId, tokenType, address, receipt.getTransactionHash());
        return new TokenDeploymentResult(receipt.getTransactionHash(), address);
    }

    private Optional<TokenDeploymentResult> adoptExisting(Web3j web3j, EvmSigner signer,
                                                          String factoryAddress, BigInteger tokenType,
                                                          String name, String symbol, byte[] assetIdBytes,
                                                          String underlyingAsset, String eventTopic,
                                                          UUID assetId) {
        Function predict = new Function("predictAddress",
                Arrays.asList(new Uint8(tokenType), new Utf8String(name), new Utf8String(symbol),
                        new Bytes32(assetIdBytes),
                        new Address(underlyingAsset != null ? underlyingAsset : Address.DEFAULT.getValue())),
                List.of(new TypeReference<Address>() {}));
        List<Type> predicted = evmContractService.call(web3j, factoryAddress, predict);
        if (predicted.isEmpty()) {
            throw new IllegalStateException("AssetTokenFactory.predictAddress returned no value at "
                    + factoryAddress);
        }
        String predictedAddress = ((Address) predicted.getFirst()).getValue();

        String code;
        try {
            code = web3j.ethGetCode(predictedAddress, DefaultBlockParameterName.LATEST).send().getCode();
        } catch (IOException e) {
            throw new RuntimeException("eth_getCode failed for " + predictedAddress + ": " + e.getMessage(), e);
        }
        if (code == null || code.isBlank() || Numeric.cleanHexPrefix(code).isEmpty()) {
            return Optional.empty();
        }

        byte[] onChainAssetId = readBytes32(web3j, predictedAddress, "assetId");
        String onChainRegistry = readAddress(web3j, predictedAddress, "registry");
        if (!Arrays.equals(onChainAssetId, assetIdBytes)
                || !signer.address().equalsIgnoreCase(onChainRegistry)) {
            throw new IllegalStateException("A contract already occupies the predicted deployment address "
                    + predictedAddress + " for asset " + assetId + " but it is not ours (assetId="
                    + Numeric.toHexString(onChainAssetId) + ", registry=" + onChainRegistry
                    + ", expected registry=" + signer.address() + "). The CREATE2 slot for this "
                    + "(assetId, tokenType) is unusable on factory " + factoryAddress
                    + "; check whether the registry authority was handed over, or deploy via a "
                    + "new factory generation.");
        }

        String txHash = findCreationTx(web3j, factoryAddress, eventTopic, assetIdBytes, predictedAddress)
                .orElseThrow(() -> new IllegalStateException("Contract " + predictedAddress
                        + " for asset " + assetId + " already exists and is ours, but its creating "
                        + "transaction could not be located via the factory's deployment event on "
                        + factoryAddress + " (RPC log range limit?). Record the deployment tx "
                        + "hash manually or retry against an archive-capable RPC."));
        log.warn("Adopting existing factory deployment for assetId={} at {} (tx={}) — created by an "
                + "earlier attempt or a third party replaying our calldata", assetId, predictedAddress, txHash);
        return Optional.of(new TokenDeploymentResult(txHash, predictedAddress));
    }

    private Optional<String> findCreationTx(Web3j web3j, String factoryAddress, String eventTopic,
                                            byte[] assetIdBytes, String deployedAddress) {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.EARLIEST,
                DefaultBlockParameterName.LATEST, factoryAddress)
                .addSingleTopic(eventTopic)
                .addSingleTopic(Numeric.toHexString(assetIdBytes));
        EthLog logs;
        try {
            logs = web3j.ethGetLogs(filter).send();
        } catch (IOException e) {
            log.warn("eth_getLogs failed while locating creation tx for {}: {}", deployedAddress, e.getMessage());
            return Optional.empty();
        }
        if (logs.hasError() || logs.getLogs() == null) {
            log.warn("eth_getLogs error while locating creation tx for {}: {}", deployedAddress,
                    logs.hasError() ? logs.getError().getMessage() : "no result");
            return Optional.empty();
        }
        String wanted = Numeric.cleanHexPrefix(deployedAddress).toLowerCase();
        for (EthLog.LogResult<?> result : logs.getLogs()) {
            if (result.get() instanceof Log entry && entry.getTopics() != null && entry.getTopics().size() > 3
                    && entry.getTopics().get(3).toLowerCase().endsWith(wanted)) {
                return Optional.ofNullable(entry.getTransactionHash());
            }
        }
        return Optional.empty();
    }

    private byte[] readBytes32(Web3j web3j, String contract, String getter) {
        List<Type> out = evmContractService.call(web3j, contract,
                new Function(getter, List.of(), List.of(new TypeReference<Bytes32>() {})));
        if (out.isEmpty()) {
            throw new IllegalStateException(getter + "() returned no value at " + contract);
        }
        return ((Bytes32) out.getFirst()).getValue();
    }

    private String readAddress(Web3j web3j, String contract, String getter) {
        List<Type> out = evmContractService.call(web3j, contract,
                new Function(getter, List.of(), List.of(new TypeReference<Address>() {})));
        if (out.isEmpty()) {
            throw new IllegalStateException(getter + "() returned no value at " + contract);
        }
        return ((Address) out.getFirst()).getValue();
    }
}
