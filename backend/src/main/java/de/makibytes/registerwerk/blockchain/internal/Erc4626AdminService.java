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
import de.makibytes.registerwerk.wallet.api.EvmSigner;
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
    private final de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;

    public Erc4626AdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetVaultStateRepository vaultStateRepository,
            VaultNavStrikeRepository navStrikeRepository,
            de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.vaultStateRepository = vaultStateRepository;
        this.navStrikeRepository = navStrikeRepository;
        this.durableTransactions = durableTransactions;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
    }

    // ── NAV strike ────────────────────────────────────────────────────────────

    /**
     * Operator strikes a new NAV per share. Submits {@code setNavPerShare} on-chain and appends a
     * {@code vault_nav_strike} history row with the tx hash — but deliberately does <b>not</b>
     * update {@code asset_vault_state.latest_nav_per_share} here: {@link EvmContractService#submit}
     * returns immediately without waiting for a receipt, so at this point the strike is only
     * submitted, not confirmed. {@code VaultConfirmationListener} applies it to
     * {@code asset_vault_state} once the tx reaches FINALIZED, and only if it's still the latest
     * strike (guards against an out-of-order confirmation clobbering a newer one).
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

        SubmittedTx tx = submitEvm(dep, fn, "setNavPerShare",
                Map.of("navPerShare", navPerShare.toPlainString(),
                        "effectiveAt", effectiveAt.toString()), actorId, actorRole);

        // Append to history, unconfirmed — asset_vault_state is updated only once
        // VaultConfirmationListener confirms this row's tx.
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
        strike.setTxHash(tx.txHash());
        navStrikeRepository.save(strike);

        return tx.txId();
    }

    // ── Deposit cap ───────────────────────────────────────────────────────────

    /**
     * Submits {@code setDepositCap} on-chain and records the in-flight value on {@code
     * asset_vault_state} — deliberately does <b>not</b> update {@code deposit_cap} itself yet
     * (same "submit returns immediately, not confirmed" reasoning as {@link #strikeNav}).
     * {@code VaultConfirmationListener} applies {@code pendingDepositCap} to {@code depositCap}
     * once confirmed. Upserts the state row (previously a silent no-op when none existed yet).
     */
    public UUID setDepositCap(UUID deploymentId, java.math.BigInteger newCap, UUID actorId, String actorRole) {
        // Lock the always-present deployment row before inspecting the optional vault-state row.
        // Locking only asset_vault_state cannot serialize the first two requests when that row
        // does not exist yet.
        AssetDeployment dep = requireDeploymentForUpdate(deploymentId);
        log.info("ERC-4626 setDepositCap={} on deployment={}", newCap, deploymentId);

        AssetVaultState state = vaultStateRepository.findByAssetIdForUpdate(dep.getAssetId())
                .orElseGet(() -> {
                    AssetVaultState created = new AssetVaultState();
                    created.setAssetId(dep.getAssetId());
                    return created;
                });
        boolean hasPendingValue = state.getPendingDepositCap() != null;
        boolean hasPendingTx = state.getDepositCapTxHash() != null;
        if (hasPendingValue != hasPendingTx) {
            throw new IllegalStateException("Deposit-cap pending state is incomplete for asset="
                    + dep.getAssetId() + "; refusing another irreversible submission");
        }
        if (hasPendingTx) {
            throw new IllegalStateException("A deposit-cap transaction is already pending for asset="
                    + dep.getAssetId() + " tx=" + state.getDepositCapTxHash());
        }

        Function fn = new Function("setDepositCap",
                Collections.singletonList(new Uint256(newCap)),
                Collections.emptyList());

        SubmittedTx tx = submitEvm(dep, fn, "setDepositCap", Map.of("newCap", newCap.toString()), actorId, actorRole);

        state.setPendingDepositCap(newCap);
        state.setDepositCapTxHash(tx.txHash());
        vaultStateRepository.save(state);

        return tx.txId();
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

    private AssetDeployment requireDeploymentForUpdate(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findByIdForUpdate(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("Vault contract not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    /** @param txId the {@code blockchain_transaction} tracking row's id (what callers of this
     *              service return to their own callers); {@code txHash} the actual on-chain
     *              transaction hash (what gets stored on the vault mirror rows so {@code
     *              VaultConfirmationListener} can poll it via {@code BlockchainTransactionService}). */
    private record SubmittedTx(UUID txId, String txHash) {}

    /** Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     *  audit log, so both strikeNav and setDepositCap reach the audit trail. */
    private SubmittedTx submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params,
                            UUID actorId, String actorRole) {
        if (dep.getChainConfigId() == null) {
            throw new IllegalStateException("Confirmed EVM deployment is missing chainConfigId: " + dep.getId());
        }
        String txHash = durableTransactions.submit(
                dep.getChainConfigId(), dep.getContractAddress(), fn, params);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        UUID txId = txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
        return new SubmittedTx(txId, txHash);
    }
}
