package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.shared.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirms pending org registrations and member-wallet bindings, and retries chain
 * broadcasts that failed after their DB intent was committed (see
 * {@link EcosystemOnchainBroadcaster} — services never broadcast before commit).
 *
 * <p>Three pending shapes exist:
 * <ul>
 *   <li>ONCHAINID still deploying (placeholder org address) — once the identity address
 *       resolves, {@code registerOrg} is submitted.</li>
 *   <li>Committed intent without a tx hash — the after-commit broadcast failed (RPC
 *       down); once the row is older than the retry grace period it is re-broadcast.</li>
 *   <li>Tx hash present — once the receipt confirms, the row reaches its final status.</li>
 * </ul>
 */
@Component
class OrgEcosystemTxPoller {

    private static final Logger log = LoggerFactory.getLogger(OrgEcosystemTxPoller.class);

    /**
     * Rows younger than this are left to their own after-commit broadcast (which runs
     * synchronously right after the commit) — retrying earlier could double-submit.
     */
    private static final Duration RETRY_GRACE = Duration.ofSeconds(90);

    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final PermissionGrantRepository grantRepository;
    private final EcosystemTrustedIssuerRepository trustedIssuerRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final Erc3643Api erc3643Api;
    private final EcosystemOnchainBroadcaster broadcaster;
    private final EvmFinalityResolver finalityResolver;
    private final ChainEffectRecorder chainEffectRecorder;

    OrgEcosystemTxPoller(
            OrgRegistrationRepository registrationRepository,
            OrgMemberWalletRepository walletRepository,
            PermissionGrantRepository grantRepository,
            EcosystemTrustedIssuerRepository trustedIssuerRepository,
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            Erc3643Api erc3643Api,
            EcosystemOnchainBroadcaster broadcaster,
            EvmFinalityResolver finalityResolver,
            ChainEffectRecorder chainEffectRecorder) {
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.grantRepository = grantRepository;
        this.trustedIssuerRepository = trustedIssuerRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.erc3643Api = erc3643Api;
        this.broadcaster = broadcaster;
        this.finalityResolver = finalityResolver;
        this.chainEffectRecorder = chainEffectRecorder;
    }

    @SchedulerLock(name = "orgEcosystemTxPoller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    @Transactional
    public void resolvePending() {
        for (OrgRegistration registration : registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)) {
            try {
                resolveRegistration(registration);
            } catch (Exception e) {
                log.warn("Failed to resolve pending org registration={}: {}", registration.getId(), e.getMessage());
            }
        }
        for (OrgMemberWallet wallet : walletRepository.findByStatus(MemberWalletStatus.PENDING)) {
            try {
                resolveWallet(wallet);
            } catch (Exception e) {
                log.warn("Failed to resolve pending member wallet={}: {}", wallet.getId(), e.getMessage());
            }
        }
        for (PermissionGrant grant : grantRepository.findByStatus(PermissionGrantStatus.PENDING)) {
            try {
                resolveGrant(grant);
            } catch (Exception e) {
                log.warn("Failed to resolve pending permission grant={}: {}", grant.getId(), e.getMessage());
            }
        }
        for (EcosystemTrustedIssuer issuer : trustedIssuerRepository.findByStatus(MemberWalletStatus.PENDING)) {
            try {
                resolveIssuer(issuer);
            } catch (Exception e) {
                log.warn("Failed to resolve pending trusted issuer={}: {}", issuer.getId(), e.getMessage());
            }
        }
        for (PermissionGrant grant : grantRepository
                .findByStatusAndRevokedTxIsNull(PermissionGrantStatus.REVOKED)) {
            if (retryDue(grant.getRevokedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRevoke(grant.getId()));
            }
        }
        for (EcosystemTrustedIssuer issuer : trustedIssuerRepository
                .findByStatusAndRemovedTxIsNull(MemberWalletStatus.REMOVED)) {
            if (retryDue(issuer.getRemovedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRemoveIssuer(issuer.getId()));
            }
        }
    }

    private void resolveGrant(PermissionGrant grant) {
        if (grant.getGrantedTx() == null) {
            if (retryDue(grant.getCreatedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastGrant(grant.getId()));
            }
            return;
        }
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId()).orElse(null);
        if (org == null) return;
        VerdictResult verdict = resolveVerdict(org.getChainConfigId(), grant.getGrantedTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                grant.setStatus(PermissionGrantStatus.ACTIVE);
                grantRepository.save(grant);
                recordEffect(org.getChainConfigId(), verdict, grant.getGrantedTx(),
                        PermissionGrantRevertCompensator.EFFECT_TYPE, "PermissionGrant", grant.getId());
            }
            case FAILED -> {
                grant.setStatus(PermissionGrantStatus.FAILED);
                grantRepository.save(grant);
            }
            case PENDING -> { /* not yet mined, or mined but not yet final — recheck next tick */ }
        }
    }

    private void resolveIssuer(EcosystemTrustedIssuer issuer) {
        if (issuer.getAddedTx() == null) {
            if (retryDue(issuer.getCreatedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastAddIssuer(issuer.getId()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(issuer.getChainConfigId(), issuer.getAddedTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                issuer.setStatus(MemberWalletStatus.ACTIVE);
                trustedIssuerRepository.save(issuer);
                recordEffect(issuer.getChainConfigId(), verdict, issuer.getAddedTx(),
                        EcosystemTrustedIssuerRevertCompensator.EFFECT_TYPE, "EcosystemTrustedIssuer", issuer.getId());
            }
            case FAILED -> {
                issuer.setStatus(MemberWalletStatus.FAILED);
                trustedIssuerRepository.save(issuer);
            }
            case PENDING -> { /* not yet mined, or mined but not yet final — recheck next tick */ }
        }
    }

    private void resolveRegistration(OrgRegistration registration) {
        if (registration.getOrgAddress().startsWith(OrgRegistrationService.PENDING_IDENTITY_PREFIX)) {
            erc3643Api.findIdentity(registration.getLegalEntityId(), registration.getChainConfigId())
                    .filter(identity -> !identity.getIdentityAddress()
                            .startsWith(OrgRegistrationService.PENDING_IDENTITY_PREFIX))
                    .ifPresent(identity -> {
                        if (identity.getIdentityAddress().startsWith("0x-FAILED-")) {
                            registration.setStatus(OrgRegistrationStatus.FAILED);
                            registrationRepository.save(registration);
                            return;
                        }
                        registration.setOrgAddress(identity.getIdentityAddress());
                        registrationRepository.save(registration);
                        // The broadcaster runs in a fresh transaction, so it must only
                        // fire once the resolved org address above is committed.
                        AfterCommit.run(() -> broadcaster.broadcastRegisterOrg(registration.getId()));
                        log.info("Scheduled deferred registerOrg for registration={} org={}",
                                registration.getId(), identity.getIdentityAddress());
                    });
            return;
        }

        if (registration.getRegisteredTx() == null) {
            if (retryDue(registration.getCreatedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRegisterOrg(registration.getId()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(registration.getChainConfigId(), registration.getRegisteredTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                registration.setStatus(OrgRegistrationStatus.ACTIVE);
                log.info("Org registration={} confirmed active (tx={})",
                        registration.getId(), registration.getRegisteredTx());
                registrationRepository.save(registration);
                recordEffect(registration.getChainConfigId(), verdict, registration.getRegisteredTx(),
                        OrgRegistrationRevertCompensator.EFFECT_TYPE, "OrgRegistration", registration.getId());
            }
            case FAILED -> {
                registration.setStatus(OrgRegistrationStatus.FAILED);
                log.error("registerOrg tx={} failed for registration={}",
                        registration.getRegisteredTx(), registration.getId());
                registrationRepository.save(registration);
            }
            case PENDING -> { /* not yet mined, or mined but not yet final — recheck next tick */ }
        }
    }

    private void resolveWallet(OrgMemberWallet wallet) {
        if (wallet.getBoundTx() == null) {
            if (retryDue(wallet.getCreatedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastAddMember(wallet.getId()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(wallet.getChainConfigId(), wallet.getBoundTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                wallet.setStatus(MemberWalletStatus.ACTIVE);
                log.info("Member wallet={} binding confirmed (tx={})", wallet.getId(), wallet.getBoundTx());
                walletRepository.save(wallet);
                recordEffect(wallet.getChainConfigId(), verdict, wallet.getBoundTx(),
                        OrgMemberWalletRevertCompensator.EFFECT_TYPE, "OrgMemberWallet", wallet.getId());
            }
            case FAILED -> {
                wallet.setStatus(MemberWalletStatus.FAILED);
                log.error("addMember tx={} failed for wallet={}", wallet.getBoundTx(), wallet.getId());
                walletRepository.save(wallet);
            }
            case PENDING -> { /* not yet mined, or mined but not yet final — recheck next tick */ }
        }
    }

    private static boolean retryDue(Instant reference) {
        return reference != null && reference.plus(RETRY_GRACE).isBefore(Instant.now());
    }

    // ── Receipt finality (shared by all four resolveX methods above) ────────────

    private enum TxVerdict { PENDING, SUCCESS, FAILED }

    /** @param blockNumber/blockHash populated only on SUCCESS — the receipt's confirming block,
     *  needed to journal a chain effect (see {@link #recordEffect}); null for PENDING/FAILED. */
    private record VerdictResult(TxVerdict verdict, Long blockNumber, String blockHash) {
        static VerdictResult of(TxVerdict verdict) { return new VerdictResult(verdict, null, null); }
    }

    /**
     * Mined-but-not-yet-final is deliberately folded into PENDING (not a fourth state): every
     * caller here re-polls every tick anyway, so "wait one more tick" and "not mined yet" need
     * no different handling — the only distinction that matters is whether it's safe to write a
     * terminal ACTIVE/FAILED status yet. Consults the chain's configured
     * {@link ChainConfig.FinalityModel} rather than accepting the first mined receipt, so a
     * reorg that un-mines this tx cannot leave an org registration, member binding, permission
     * grant, or trusted issuer ACTIVE on a state the chain has since abandoned.
     */
    private VerdictResult resolveVerdict(UUID chainConfigId, String txHash) {
        ChainConfig chainConfig = chainConfigRepository.findById(chainConfigId).orElse(null);
        if (chainConfig == null) return VerdictResult.of(TxVerdict.PENDING);

        Web3j web3j;
        try {
            web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
        } catch (Exception e) {
            log.debug("EVM client not available for chain={}", chainConfig.getIdentifier());
            return VerdictResult.of(TxVerdict.PENDING);
        }
        try {
            Optional<TransactionReceipt> receiptOpt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                return VerdictResult.of(TxVerdict.PENDING);
            }
            TransactionReceipt receipt = receiptOpt.get();
            if (!"0x1".equals(receipt.getStatus())) {
                return VerdictResult.of(TxVerdict.FAILED);
            }

            long blockNumber = receipt.getBlockNumber().longValueExact();
            boolean isFinal = finalityResolver.levelOf(chainConfig.getIdentifier(), web3j, blockNumber)
                    .atLeast(FinalityLevel.FINALIZED);
            return isFinal
                    ? new VerdictResult(TxVerdict.SUCCESS, blockNumber, receipt.getBlockHash())
                    : VerdictResult.of(TxVerdict.PENDING);
        } catch (Exception e) {
            log.debug("Receipt lookup failed for tx={}: {}", txHash, e.getMessage());
            return VerdictResult.of(TxVerdict.PENDING);
        }
    }

    /**
     * Journals an INVERSE_FLIP chain effect for a just-confirmed row so a reorg deep enough to
     * retract its already-FINALIZED block reverts it back to PENDING instead of the register
     * asserting a confirmation the chain no longer agrees happened — see the four
     * {@code *RevertCompensator} classes in this package that undo each effect type. Silently
     * skipped (not an error) if the verdict somehow carries no block number — cannot happen on the
     * SUCCESS path that calls this, but keeps the method total rather than throwing.
     */
    private void recordEffect(UUID chainConfigId, VerdictResult verdict, String txHash,
            String effectType, String entityType, UUID entityId) {
        if (verdict.blockNumber() == null) {
            return;
        }
        chainEffectRecorder.record(ChainEffectDescriptor.of(
                chainConfigId, verdict.blockNumber(), verdict.blockHash(), txHash,
                "orgidentity", effectType, entityType, entityId, null,
                CompensationCategory.INVERSE_FLIP));
    }
}
