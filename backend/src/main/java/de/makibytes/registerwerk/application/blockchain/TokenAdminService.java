package de.makibytes.registerwerk.application.blockchain;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>All operations require the registry wallet (configured via {@code registerwerk.wallet.private-key})
 * and target the {@code EwpgCompliance} functions inherited by every token contract, or the
 * force-operation functions defined per token type.
 *
 * <p>Regulatory basis:
 * <ul>
 *   <li>{@link #pause} / {@link #unpause}     — MiCAR Art. 36, 84; eWpG §24</li>
 *   <li>{@link #freezeAddress}                — AWG §17, GwG §40; MiCAR Art. 36</li>
 *   <li>{@link #unfreezeAddress}              — lift sanctions freeze</li>
 *   <li>{@link #forcedTransfer}               — eWpG §24 Berichtigung (BaFin/court order)</li>
 *   <li>{@link #forceBurn}                    — eWpG §26 Einziehung (compulsory cancellation)</li>
 *   <li>{@link #setSupplyCap}                 — MiCAR Art. 46 (regulatory issuance ceiling)</li>
 * </ul>
 *
 * <p>For ERC-3643 tokens these operations are handled by
 * {@link de.makibytes.registerwerk.application.erc3643.Erc3643LifecycleService}, which uses
 * T-REX's built-in agent model.
 *
 * <p>Solana (SPL) tokens support address freeze / thaw natively at the protocol level via
 * the SPL Token freeze-authority; those operations are not yet implemented in this service.
 */
@Service
@Transactional
public class TokenAdminService {

    private static final Logger log = LoggerFactory.getLogger(TokenAdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetRepository assetRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final AuditEventPublisher auditPublisher;

    public TokenAdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetRepository assetRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            AuditEventPublisher auditPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.assetRepository = assetRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.auditPublisher = auditPublisher;
    }

    // ── Pause / Unpause (MiCAR Art. 36, 84; eWpG §24) ────────────────────────

    /**
     * Suspends all transfers on the token contract.
     *
     * <p>Legal basis: MiCAR Art. 36, 84; eWpG §24. Invoke on BaFin emergency order
     * or when a systemic risk is detected.
     *
     * @param deploymentId ID of the {@link AssetDeployment} whose contract to pause
     */
    public void pause(UUID deploymentId) {
        log.info("ADMIN pause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        sendAdmin(dep, new Function("pause", Collections.emptyList(), Collections.emptyList()));

        auditPublisher.publish("TOKEN_PAUSED", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("contractAddress", dep.getContractAddress()));
    }

    /**
     * Resumes normal transfers on the token contract.
     *
     * @param deploymentId ID of the {@link AssetDeployment} to unpause
     */
    public void unpause(UUID deploymentId) {
        log.info("ADMIN unpause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        sendAdmin(dep, new Function("unpause", Collections.emptyList(), Collections.emptyList()));

        auditPublisher.publish("TOKEN_UNPAUSED", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("contractAddress", dep.getContractAddress()));
    }

    // ── Address freeze (AWG §17, GwG §40; MiCAR Art. 36) ─────────────────────

    /**
     * Freezes {@code walletAddress} on-chain, blocking all incoming and outgoing transfers.
     *
     * <p>Legal basis: AWG §17 (sanctions list enforcement); GwG §40 (suspicious transaction
     * freeze); MiCAR Art. 36 (competent-authority asset freeze).
     *
     * @param deploymentId  ID of the asset deployment
     * @param walletAddress EVM address to freeze
     * @param reason        Short reason string written on-chain (e.g. "OFAC SDN", "BaFin AML §40")
     * @param legalBasis    Reference stored in audit log (e.g. "BaFin Az. 2025-001")
     */
    public void freezeAddress(UUID deploymentId, String walletAddress, String reason, String legalBasis) {
        log.info("ADMIN freezeAddress={} on deployment={} reason={}", walletAddress, deploymentId, reason);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        Function fn = new Function(
                "freezeAddress",
                Arrays.asList(new Address(walletAddress), new Utf8String(reason)),
                Collections.emptyList()
        );
        sendAdmin(dep, fn);

        auditPublisher.publish("ADDRESS_FROZEN", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("address", walletAddress, "reason", reason, "legalBasis", legalBasis));
    }

    /**
     * Lifts a previous freeze on {@code walletAddress}.
     *
     * @param deploymentId  ID of the asset deployment
     * @param walletAddress EVM address to unfreeze
     */
    public void unfreezeAddress(UUID deploymentId, String walletAddress) {
        log.info("ADMIN unfreezeAddress={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        Function fn = new Function(
                "unfreezeAddress",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList()
        );
        sendAdmin(dep, fn);

        auditPublisher.publish("ADDRESS_UNFROZEN", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("address", walletAddress));
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    /**
     * Transfers tokens/NFT from {@code from} to {@code to}, bypassing whitelist, freeze, and pause.
     *
     * <p>Legal basis: eWpG §24 Berichtigung — BaFin or court-ordered correction of the
     * electronic securities register.
     *
     * <p>For ERC-721 set {@code value} to the tokenId. For ERC-1155 pass the amount for
     * token id 0; for other ids use the dedicated ERC-1155 endpoint.
     *
     * @param deploymentId ID of the asset deployment
     * @param from         Source wallet address
     * @param to           Destination wallet address
     * @param value        Amount (ERC-20 / ERC-1155) or tokenId (ERC-721)
     * @param legalBasis   Reference to the BaFin/court order (stored in audit log and on-chain event)
     */
    public void forcedTransfer(UUID deploymentId, String from, String to, BigInteger value, String legalBasis) {
        log.info("ADMIN forcedTransfer from={} to={} value={} on deployment={} legalBasis={}",
                from, to, value, deploymentId, legalBasis);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        Function fn = new Function(
                "forcedTransfer",
                Arrays.asList(new Address(from), new Address(to), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList()
        );
        sendAdmin(dep, fn);

        auditPublisher.publish("FORCED_TRANSFER", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("from", from, "to", to, "value", value.toString(), "legalBasis", legalBasis));
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    /**
     * Burns tokens from {@code from}, bypassing whitelist, freeze, and pause.
     *
     * <p>Legal basis: eWpG §26 Einziehung — BaFin compulsory cancellation of electronic securities.
     *
     * @param deploymentId ID of the asset deployment
     * @param from         Wallet address whose tokens are cancelled
     * @param value        Amount (ERC-20 / ERC-1155) or tokenId (ERC-721)
     * @param legalBasis   Reference to the BaFin Einziehungsverfügung (stored on-chain + audit log)
     */
    public void forceBurn(UUID deploymentId, String from, BigInteger value, String legalBasis) {
        log.info("ADMIN forceBurn from={} value={} on deployment={} legalBasis={}",
                from, value, deploymentId, legalBasis);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        Function fn = new Function(
                "forceBurn",
                Arrays.asList(new Address(from), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList()
        );
        sendAdmin(dep, fn);

        auditPublisher.publish("FORCE_BURN", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("from", from, "value", value.toString(), "legalBasis", legalBasis));
    }

    // ── Supply cap — MiCAR Art. 46 ────────────────────────────────────────────

    /**
     * Updates the maximum total supply of the token.
     *
     * <p>Legal basis: MiCAR Art. 46 — competent authority can impose regulatory cap
     * on the number of crypto-assets in circulation.
     *
     * @param deploymentId ID of the asset deployment
     * @param newCap       New supply ceiling in smallest token unit; 0 removes any cap
     */
    public void setSupplyCap(UUID deploymentId, BigInteger newCap) {
        log.info("ADMIN setSupplyCap={} on deployment={}", newCap, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvmToken(dep);

        Function fn = new Function(
                "setSupplyCap",
                Collections.singletonList(new Uint256(newCap)),
                Collections.emptyList()
        );
        sendAdmin(dep, fn);

        auditPublisher.publish("SUPPLY_CAP_SET", "AssetDeployment", deploymentId, null, "REGISTRY_ADMIN",
                Map.of("newCap", newCap.toString()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    /**
     * Rejects the operation if the deployment's token standard is not an EVM type that
     * implements {@code IEwpgAdminControls} (i.e. ERC-20, ERC-721, ERC-1155).
     * ERC-3643 uses its own lifecycle service. Confidential and SPL tokens are not yet supported.
     */
    private void requireEvmToken(AssetDeployment dep) {
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
                    "SPL token admin controls (freeze-authority) are not yet implemented. "
                    + "Use the Solana CLI or `spl-token freeze` / `spl-token thaw` commands.");
        }
        if (dep.getContractAddress() == null || dep.getContractAddress().startsWith("0x-PENDING")) {
            throw new IllegalStateException("Token contract is not yet deployed for deployment=" + dep.getId());
        }
    }

    private void sendAdmin(AssetDeployment dep, Function fn) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials();
        evmContractService.send(web3j, creds, dep.getContractAddress(), fn);
    }
}
