package de.makibytes.registerwerk.blockchain.internal;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>All write operations submit transactions asynchronously and return a tracking UUID.
 * The client polls {@code GET /api/v1/transactions/{txId}} for status.
 *
 * <p>Regulatory basis:
 * <ul>
 *   <li>{@link #pause} / {@link #unpause}     — MiCAR Art. 36, 84; eWpG §24</li>
 *   <li>{@link #freezeAddress}                — AWG §17, GwG §40; MiCAR Art. 36</li>
 *   <li>{@link #unfreezeAddress}              — lift sanctions freeze</li>
 *   <li>{@link #whitelist}                    — add to on-chain transfer whitelist</li>
 *   <li>{@link #unwhitelist}                  — remove from on-chain transfer whitelist</li>
 *   <li>{@link #forcedTransfer}               — eWpG §24 Berichtigung (BaFin/court order)</li>
 *   <li>{@link #forcedTransferSingle}         — ERC-1155: forced transfer of specific token id</li>
 *   <li>{@link #forcedApprove}                — regulatory approval override</li>
 *   <li>{@link #forceBurn}                    — eWpG §26 Einziehung (compulsory cancellation)</li>
 *   <li>{@link #forceBurnSingle}              — ERC-1155: forced burn of specific token id</li>
 *   <li>{@link #setSupplyCap}                 — MiCAR Art. 46 (regulatory issuance ceiling)</li>
 * </ul>
 */
@Service
@Transactional
public class TokenAdminService {

    private static final Logger log = LoggerFactory.getLogger(TokenAdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetRepository assetRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;

    public TokenAdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetRepository assetRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService) {
        this.deploymentRepository = deploymentRepository;
        this.assetRepository = assetRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
    }

    // ── Pause / Unpause (MiCAR Art. 36, 84; eWpG §24) ────────────────────────

    public UUID pause(UUID deploymentId) {
        log.info("ADMIN pause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        return submitAdmin(dep, asset, new Function("pause", Collections.emptyList(), Collections.emptyList()),
                "pause", Map.of());
    }

    public UUID unpause(UUID deploymentId) {
        log.info("ADMIN unpause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        return submitAdmin(dep, asset, new Function("unpause", Collections.emptyList(), Collections.emptyList()),
                "unpause", Map.of());
    }

    // ── Address freeze (AWG §17, GwG §40; MiCAR Art. 36) ─────────────────────

    public UUID freezeAddress(UUID deploymentId, String walletAddress, String reason, String legalBasis) {
        log.info("ADMIN freezeAddress={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("freezeAddress",
                Arrays.asList(new Address(walletAddress), new Utf8String(reason)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "freezeAddress",
                Map.of("address", walletAddress, "reason", reason, "legalBasis", legalBasis));
    }

    public UUID unfreezeAddress(UUID deploymentId, String walletAddress) {
        log.info("ADMIN unfreezeAddress={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("unfreezeAddress",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "unfreezeAddress", Map.of("address", walletAddress));
    }

    // ── Whitelist management ──────────────────────────────────────────────────

    /** Adds {@code walletAddress} to the on-chain transfer whitelist. */
    public UUID whitelist(UUID deploymentId, String walletAddress) {
        log.info("ADMIN whitelist={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("whitelist",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "whitelist", Map.of("address", walletAddress));
    }

    /** Removes {@code walletAddress} from the on-chain transfer whitelist. */
    public UUID unwhitelist(UUID deploymentId, String walletAddress) {
        log.info("ADMIN removeFromWhitelist={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("removeFromWhitelist",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "removeFromWhitelist", Map.of("address", walletAddress));
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    public UUID forcedTransfer(UUID deploymentId, String from, String to, BigInteger value, String legalBasis) {
        log.info("ADMIN forcedTransfer from={} to={} value={} on deployment={}", from, to, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function(
                forcedTransferMethodName(asset.getTokenStandard()),
                Arrays.asList(new Address(from), new Address(to), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedTransfer",
                Map.of("from", from, "to", to, "value", value.toString(), "legalBasis", legalBasis));
    }

    /**
     * ERC-1155 only: forces transfer of {@code amount} of token {@code id} from {@code from} to {@code to}.
     */
    public UUID forcedTransferSingle(UUID deploymentId, String from, String to,
                                     BigInteger id, BigInteger amount, String legalBasis) {
        log.info("ADMIN forcedTransferSingle id={} amount={} on deployment={}", id, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        if (asset.getTokenStandard() != TokenStandard.ERC1155) {
            throw new IllegalArgumentException("forcedTransferSingle is only available for ERC-1155 tokens");
        }
        Function fn = new Function("forcedTransferSingle",
                Arrays.asList(new Address(from), new Address(to), new Uint256(id),
                        new Uint256(amount), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedTransferSingle",
                Map.of("from", from, "to", to, "id", id.toString(),
                        "amount", amount.toString(), "legalBasis", legalBasis));
    }

    // ── Forced approve ────────────────────────────────────────────────────────

    public UUID forcedApprove(UUID deploymentId, String owner, String spender, BigInteger value, String legalBasis) {
        log.info("ADMIN forcedApprove owner={} spender={} value={} on deployment={}", owner, spender, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function(
                forcedApproveMethodName(asset.getTokenStandard()),
                Arrays.asList(new Address(owner), new Address(spender), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedApprove",
                Map.of("owner", owner, "spender", spender, "value", value.toString(), "legalBasis", legalBasis));
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    public UUID forceBurn(UUID deploymentId, String from, BigInteger value, String legalBasis) {
        log.info("ADMIN forceBurn from={} value={} on deployment={}", from, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("forceBurn",
                Arrays.asList(new Address(from), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forceBurn",
                Map.of("from", from, "value", value.toString(), "legalBasis", legalBasis));
    }

    /**
     * ERC-1155 only: forces burn of {@code amount} of token {@code id} from {@code from}.
     */
    public UUID forceBurnSingle(UUID deploymentId, String from, BigInteger id, BigInteger amount, String legalBasis) {
        log.info("ADMIN forceBurnSingle id={} amount={} on deployment={}", id, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        if (asset.getTokenStandard() != TokenStandard.ERC1155) {
            throw new IllegalArgumentException("forceBurnSingle is only available for ERC-1155 tokens");
        }
        Function fn = new Function("forceBurnSingle",
                Arrays.asList(new Address(from), new Uint256(id), new Uint256(amount), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forceBurnSingle",
                Map.of("from", from, "id", id.toString(), "amount", amount.toString(), "legalBasis", legalBasis));
    }

    // ── Standard issuance (issuer minting/burning) ────────────────────────────

    public UUID mint(UUID deploymentId, String toAddress, BigInteger amount) {
        log.info("Issuer MINT to={} amount={} on deployment={}", toAddress, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("mint",
                Arrays.asList(new Address(toAddress), new Uint256(amount)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "mint", Map.of("toAddress", toAddress, "amount", amount.toString()));
    }

    public UUID regularBurn(UUID deploymentId, String fromAddress, BigInteger amount) {
        log.info("Issuer BURN from={} amount={} on deployment={}", fromAddress, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("burn",
                Arrays.asList(new Address(fromAddress), new Uint256(amount)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "burn", Map.of("fromAddress", fromAddress, "amount", amount.toString()));
    }

    // ── Supply cap — MiCAR Art. 46 ────────────────────────────────────────────

    public UUID setSupplyCap(UUID deploymentId, BigInteger newCap) {
        log.info("ADMIN setSupplyCap={} on deployment={}", newCap, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        Asset asset = requireEvmToken(dep);
        Function fn = new Function("setSupplyCap",
                Collections.singletonList(new Uint256(newCap)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "setSupplyCap", Map.of("newCap", newCap.toString()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private Asset requireEvmToken(AssetDeployment dep) {
        Asset asset = assetRepository.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()));
        TokenStandard standard = asset.getTokenStandard();
        if (standard == TokenStandard.ERC3643 || standard == TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException(
                    "ERC-3643 admin operations go through Erc3643LifecycleService (/erc3643 endpoints).");
        }
        if (standard == TokenStandard.CONF_ERC20) {
            throw new UnsupportedOperationException(
                    "Confidential ERC-20 admin controls require Zama fhEVM support (not yet implemented).");
        }
        if (standard == TokenStandard.SPL) {
            throw new UnsupportedOperationException(
                    "SPL token admin controls (freeze-authority) are not yet implemented.");
        }
        if (dep.getContractAddress() == null || dep.getContractAddress().startsWith("0x-PENDING")) {
            throw new IllegalStateException("Token contract is not yet deployed for deployment=" + dep.getId());
        }
        return asset;
    }

    private UUID submitAdmin(AssetDeployment dep, Asset asset, Function fn, String methodName, Map<String, Object> params) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, dep.getContractAddress(), fn);

        return txService.record(txHash, methodName, dep.getId(), asset.getId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }

    private static String forcedTransferMethodName(TokenStandard standard) {
        return switch (standard) {
            case ERC20, ERC721, ERC1155 -> "forcedTransfer";
            default -> throw new UnsupportedOperationException(
                    "Forced transfer method mapping is not defined for token standard: " + standard);
        };
    }

    private static String forcedApproveMethodName(TokenStandard standard) {
        return switch (standard) {
            case ERC20, ERC721, ERC1155 -> "forcedApprove";
            default -> throw new UnsupportedOperationException(
                    "Forced approve method mapping is not defined for token standard: " + standard);
        };
    }
}
