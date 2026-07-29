package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Registry-operator administrative controls for EwpgERC4626 (sync vault) contracts.
 *
 * <p>Key operation: {@link #strikeNav} — operator submits a new NAV per share, anchoring
 * an off-chain report hash on-chain. This is the primary lifecycle action for fund vaults.
 */
@Service
@Transactional
public class Erc4626AdminService implements de.makibytes.registerwerk.blockchain.api.Erc4626AdminPort {

    private static final Logger log = LoggerFactory.getLogger(Erc4626AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetVaultStateRepository vaultStateRepository;
    private final VaultNavStrikeRepository navStrikeRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;

    public Erc4626AdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetVaultStateRepository vaultStateRepository,
            VaultNavStrikeRepository navStrikeRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.vaultStateRepository = vaultStateRepository;
        this.navStrikeRepository = navStrikeRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
    }

    // ── NAV strike ────────────────────────────────────────────────────────────

    /**
     * Operator strikes a new NAV per share. Both on-chain (via {@code setNavPerShare}) and
     * the off-chain mirror ({@code vault_nav_strike}, {@code asset_vault_state}) are updated.
     *
     * @param deploymentId  ID of the ERC-4626 or ERC-7540 deployment.
     * @param navPerShare   New NAV in fixed-point (1e18 = 1.0).
     * @param effectiveAt   Timestamp when this NAV becomes effective.
     * @param reportHash    keccak256 of the off-chain NAV attestation report.
     * @param reportDocId   ID of the uploaded AssetDocument for the report (nullable).
     * @param actorId       ID of the operator user performing the strike.
     * @return Blockchain transaction tracking ID.
     */
    public UUID strikeNav(UUID deploymentId, BigDecimal navPerShare, Instant effectiveAt,
                          byte[] reportHash, UUID reportDocId, UUID actorId, String actorRole) {
        log.info("NAV strike: deploymentId={} navPerShare={} effectiveAt={}", deploymentId, navPerShare, effectiveAt);

        AssetDeployment dep = requireDeployment(deploymentId);
        UUID assetId = dep.getAssetId();

        // Convert navPerShare to on-chain fixed-point (1e18 scale) BigInteger
        java.math.BigInteger navOnChain = navPerShare
                .multiply(new BigDecimal("1000000000000000000"))
                .toBigInteger();

        long effectiveAtEpoch = effectiveAt.getEpochSecond();
        byte[] reportHashBytes = (reportHash != null) ? reportHash : new byte[32];

        Function fn = new Function("setNavPerShare",
                Arrays.asList(
                        new Uint256(navOnChain),
                        new Uint256(effectiveAtEpoch),
                        new Bytes32(reportHashBytes)
                ),
                Collections.emptyList());

        UUID txId = submitEvm(dep, fn, "setNavPerShare",
                Map.of("navPerShare", navPerShare.toPlainString(),
                        "effectiveAt", effectiveAt.toString()), actorId, actorRole);

        // Update off-chain mirrors
        AssetVaultState state = vaultStateRepository.findById(assetId)
                .orElseGet(() -> { AssetVaultState s = new AssetVaultState(); s.setAssetId(assetId); return s; });
        state.setLatestNavPerShare(navPerShare);
        state.setLatestNavStrikeAt(effectiveAt);
        state.setLatestNavReportHash(reportHashBytes);
        vaultStateRepository.save(state);

        // Append to history
        long strikeId = navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(assetId).size() + 1L;
        VaultNavStrike strike = new VaultNavStrike();
        strike.setAssetId(assetId);
        strike.setStrikeId(strikeId);
        strike.setNavPerShare(navPerShare);
        strike.setEffectiveAt(effectiveAt);
        strike.setReportHash(reportHashBytes);
        strike.setReportDocId(reportDocId);
        strike.setStruckBy(actorId);
        strike.setStruckAt(Instant.now());
        navStrikeRepository.save(strike);

        return txId;
    }

    // ── Deposit cap ───────────────────────────────────────────────────────────

    public UUID setDepositCap(UUID deploymentId, java.math.BigInteger newCap, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-4626 setDepositCap={} on deployment={}", newCap, deploymentId);

        Function fn = new Function("setDepositCap",
                Collections.singletonList(new Uint256(newCap)),
                Collections.emptyList());

        UUID txId = submitEvm(dep, fn, "setDepositCap", Map.of("newCap", newCap.toString()), actorId, actorRole);

        vaultStateRepository.findById(dep.getAssetId()).ifPresent(state -> {
            state.setDepositCap(newCap);
            vaultStateRepository.save(state);
        });

        return txId;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("Vault contract not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    /** Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     *  audit log, so both strikeNav and setDepositCap reach the audit trail. */
    private UUID submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params,
                            UUID actorId, String actorRole) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, dep.getContractAddress(), fn);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        return txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }
}
