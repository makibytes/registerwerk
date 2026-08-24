package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
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
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import de.makibytes.registerwerk.orgidentity.events.OrgChainTransitionFinalizedEvent;
import de.makibytes.registerwerk.shared.AfterCommit;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final ApplicationEventPublisher eventPublisher;
    private final ChainQuarantinePort chainQuarantine;
    private final TransactionTemplate rowTransactions;

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
            ChainEffectRecorder chainEffectRecorder,
            ApplicationEventPublisher eventPublisher,
            ChainQuarantinePort chainQuarantine,
            PlatformTransactionManager transactionManager) {
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
        this.eventPublisher = eventPublisher;
        this.chainQuarantine = chainQuarantine;
        this.rowTransactions = new TransactionTemplate(transactionManager);
        this.rowTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @SchedulerLock(name = "orgEcosystemTxPoller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void resolvePending() {
        for (OrgRegistration registration : registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)) {
            resolveSafely("pending org registration", registration.getId(),
                    () -> resolveRegistration(registration));
        }
        for (OrgMemberWallet wallet : walletRepository.findByStatus(MemberWalletStatus.PENDING)) {
            resolveSafely("pending member wallet", wallet.getId(), () -> resolveWallet(wallet));
        }
        for (PermissionGrant grant : grantRepository.findByStatus(PermissionGrantStatus.PENDING)) {
            resolveSafely("pending permission grant", grant.getId(), () -> resolveGrant(grant));
        }
        for (EcosystemTrustedIssuer issuer : trustedIssuerRepository.findByStatus(TrustedIssuerStatus.PENDING)) {
            resolveSafely("pending trusted issuer", issuer.getId(), () -> resolveIssuer(issuer));
        }
        for (PermissionGrant grant : grantRepository.findByStatus(PermissionGrantStatus.REVOCATION_PENDING)) {
            resolveSafely("permission revocation", grant.getId(), () -> resolveGrantRevocation(grant));
        }
        for (EcosystemTrustedIssuer issuer : trustedIssuerRepository.findByStatus(TrustedIssuerStatus.REMOVAL_PENDING)) {
            resolveSafely("trusted issuer removal", issuer.getId(), () -> resolveIssuerRemoval(issuer));
        }
        for (OrgRegistration registration : registrationRepository.findByStatus(OrgRegistrationStatus.SUSPEND_PENDING)) {
            resolveSafely("org suspension", registration.getId(), () -> resolveOrgStatus(registration));
        }
        for (OrgRegistration registration : registrationRepository.findByStatus(OrgRegistrationStatus.REINSTATE_PENDING)) {
            resolveSafely("org reinstatement", registration.getId(), () -> resolveOrgStatus(registration));
        }
        for (OrgMemberWallet wallet : walletRepository.findByStatus(MemberWalletStatus.REMOVAL_PENDING)) {
            resolveSafely("member removal", wallet.getId(), () -> resolveWalletRemoval(wallet));
        }
        for (PermissionGrant grant : grantRepository.findByRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING)) {
            resolveSafely("role restriction", grant.getId(), () -> resolveRoleRestriction(grant));
        }
    }

    private void resolveSafely(String action, UUID id, Runnable resolver) {
        try {
            rowTransactions.executeWithoutResult(status -> resolver.run());
        } catch (Exception e) {
            log.warn("Failed to resolve {}={}: {}", action, id, e.getMessage());
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
                grant.setGrantedChainConfigId(org.getChainConfigId());
                grant.setGrantedBlockNumber(verdict.blockNumber());
                grant.setGrantedBlockHash(verdict.blockHash());
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
                issuer.setStatus(TrustedIssuerStatus.ACTIVE);
                issuer.setAddedBlockNumber(verdict.blockNumber());
                issuer.setAddedBlockHash(verdict.blockHash());
                trustedIssuerRepository.save(issuer);
                recordEffect(issuer.getChainConfigId(), verdict, issuer.getAddedTx(),
                        EcosystemTrustedIssuerRevertCompensator.EFFECT_TYPE, "EcosystemTrustedIssuer", issuer.getId());
            }
            case FAILED -> {
                issuer.setStatus(TrustedIssuerStatus.FAILED);
                trustedIssuerRepository.save(issuer);
            }
            case PENDING -> { /* not yet mined, or mined but not yet final — recheck next tick */ }
        }
    }

    private void resolveGrantRevocation(PermissionGrant grant) {
        if (grant.getRevokedTx() == null) {
            if (retryDue(grant.getRevokedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRevoke(grant.getId()));
            }
            return;
        }
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId()).orElse(null);
        if (org == null) return;
        VerdictResult verdict = resolveVerdict(org.getChainConfigId(), grant.getRevokedTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                grant.setStatus(PermissionGrantStatus.REVOKED);
                grant.setRevokedChainConfigId(org.getChainConfigId());
                grant.setRevokedBlockNumber(verdict.blockNumber());
                grant.setRevokedBlockHash(verdict.blockHash());
                grantRepository.save(grant);
                recordEffect(org.getChainConfigId(), verdict, grant.getRevokedTx(),
                        PermissionRevocationRevertCompensator.EFFECT_TYPE,
                        "PermissionGrant", grant.getId());
            }
            case FAILED -> {
                grant.setStatus(PermissionGrantStatus.REVOCATION_FAILED);
                grant.setRevokedChainConfigId(null);
                grant.setRevokedBlockNumber(null);
                grant.setRevokedBlockHash(null);
                grantRepository.save(grant);
            }
            case PENDING -> { /* not yet mined or finalized */ }
        }
    }

    private void resolveIssuerRemoval(EcosystemTrustedIssuer issuer) {
        if (issuer.getRemovedTx() == null) {
            if (retryDue(issuer.getRemovedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRemoveIssuer(issuer.getId()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(issuer.getChainConfigId(), issuer.getRemovedTx());
        switch (verdict.verdict()) {
            case SUCCESS -> {
                issuer.setStatus(TrustedIssuerStatus.REMOVED);
                issuer.setRemovedBlockNumber(verdict.blockNumber());
                issuer.setRemovedBlockHash(verdict.blockHash());
                trustedIssuerRepository.save(issuer);
                recordEffect(issuer.getChainConfigId(), verdict, issuer.getRemovedTx(),
                        TrustedIssuerRemovalRevertCompensator.EFFECT_TYPE,
                        "EcosystemTrustedIssuer", issuer.getId());
            }
            case FAILED -> {
                issuer.setStatus(TrustedIssuerStatus.REMOVAL_FAILED);
                issuer.setRemovedBlockNumber(null);
                issuer.setRemovedBlockHash(null);
                trustedIssuerRepository.save(issuer);
            }
            case PENDING -> { /* not yet mined or finalized */ }
        }
    }

    private void resolveOrgStatus(OrgRegistration registration) {
        boolean suspending = registration.getStatus() == OrgRegistrationStatus.SUSPEND_PENDING;
        if (!suspending && registration.getStatus() != OrgRegistrationStatus.REINSTATE_PENDING) return;
        if (registration.getStatusTx() == null) {
            if (retryDue(registration.getStatusRequestedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastOrgStatus(
                        registration.getId(), registration.getSuspensionReason()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(registration.getChainConfigId(), registration.getStatusTx());
        if (verdict.verdict() == TxVerdict.PENDING) return;

        registration.setStatusChainConfigId(registration.getChainConfigId());
        registration.setStatusBlockNumber(verdict.blockNumber());
        registration.setStatusBlockHash(verdict.blockHash());
        if (verdict.verdict() == TxVerdict.SUCCESS) {
            registration.setStatus(suspending
                    ? OrgRegistrationStatus.SUSPENDED : OrgRegistrationStatus.ACTIVE);
            if (!suspending) {
                registration.setSuspendedAt(null);
                registration.setSuspendedBy(null);
                registration.setSuspensionReason(null);
            }
            registrationRepository.save(registration);
            recordEffect(registration.getChainConfigId(), verdict, registration.getStatusTx(),
                    suspending ? OrgSuspensionRevertCompensator.EFFECT_TYPE
                            : OrgReinstatementRevertCompensator.EFFECT_TYPE,
                    "OrgRegistration", registration.getId());
        } else {
            registration.setStatus(suspending
                    ? OrgRegistrationStatus.SUSPEND_FAILED : OrgRegistrationStatus.REINSTATE_FAILED);
            registrationRepository.save(registration);
        }
        publishFinalized(registration.getId(), "OrgRegistration",
                suspending ? "SUSPEND" : "REINSTATE", verdict, registration.getStatusTx());
    }

    private void resolveWalletRemoval(OrgMemberWallet wallet) {
        if (wallet.getRemovedTx() == null) {
            if (retryDue(wallet.getRemovedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRemoveMember(wallet.getId()));
            }
            return;
        }
        VerdictResult verdict = resolveVerdict(wallet.getChainConfigId(), wallet.getRemovedTx());
        if (verdict.verdict() == TxVerdict.PENDING) return;

        wallet.setRemovedChainConfigId(wallet.getChainConfigId());
        wallet.setRemovedBlockNumber(verdict.blockNumber());
        wallet.setRemovedBlockHash(verdict.blockHash());
        if (verdict.verdict() == TxVerdict.SUCCESS) {
            wallet.setStatus(MemberWalletStatus.REMOVED);
            walletRepository.save(wallet);
            recordEffect(wallet.getChainConfigId(), verdict, wallet.getRemovedTx(),
                    MemberWalletRemovalRevertCompensator.EFFECT_TYPE,
                    "OrgMemberWallet", wallet.getId());
        } else {
            wallet.setStatus(MemberWalletStatus.REMOVAL_FAILED);
            walletRepository.save(wallet);
        }
        publishFinalized(wallet.getId(), "OrgMemberWallet", "REMOVE_MEMBER", verdict, wallet.getRemovedTx());
    }

    private void resolveRoleRestriction(PermissionGrant grant) {
        if (grant.getRequestedRoleRestricted() == null) {
            throw new IllegalStateException("Pending role restriction has no desired value");
        }
        if (grant.getRoleRestrictionTx() == null) {
            if (retryDue(grant.getRoleRestrictionRequestedAt())) {
                AfterCommit.run(() -> broadcaster.broadcastRoleRestriction(grant.getId()));
            }
            return;
        }
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId()).orElse(null);
        if (org == null) return;
        VerdictResult verdict = resolveVerdict(org.getChainConfigId(), grant.getRoleRestrictionTx());
        if (verdict.verdict() == TxVerdict.PENDING) return;

        grant.setRoleRestrictionChainConfigId(org.getChainConfigId());
        grant.setRoleRestrictionBlockNumber(verdict.blockNumber());
        grant.setRoleRestrictionBlockHash(verdict.blockHash());
        if (verdict.verdict() == TxVerdict.SUCCESS) {
            boolean before = grant.isConfirmedRoleRestricted();
            boolean confirmed = grant.getRequestedRoleRestricted();
            grant.setRoleRestricted(confirmed);
            grant.setRoleRestrictionStatus(RoleRestrictionStatus.STABLE);
            grant.setRequestedRoleRestricted(null);
            grantRepository.save(grant);
            chainEffectRecorder.recordFinalized(new ChainEffectDescriptor(
                    org.getChainConfigId(), verdict.blockNumber(), verdict.blockHash(),
                    grant.getRoleRestrictionTx(), null, "orgidentity",
                    RoleRestrictionRevertCompensator.EFFECT_TYPE, "PermissionGrant", grant.getId(), null,
                    CompensationCategory.INVERSE_FLIP, java.util.Map.of("roleRestricted", before),
                    java.util.Map.of("roleRestricted", confirmed), null, null));
        } else {
            grant.setRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_FAILED);
            grantRepository.save(grant);
        }
        publishFinalized(grant.getId(), "PermissionGrant", "SET_ROLE_RESTRICTED",
                verdict, grant.getRoleRestrictionTx());
    }

    private void publishFinalized(UUID subjectId, String subjectType, String transition,
                                  VerdictResult verdict, String txHash) {
        eventPublisher.publishEvent(new OrgChainTransitionFinalizedEvent(
                subjectId, subjectType, transition, verdict.verdict() == TxVerdict.SUCCESS,
                txHash, verdict.blockNumber(), verdict.blockHash()));
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
                registration.setConfirmedBlockNumber(verdict.blockNumber());
                registration.setConfirmedBlockHash(verdict.blockHash());
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
                wallet.setBoundBlockNumber(verdict.blockNumber());
                wallet.setBoundBlockHash(verdict.blockHash());
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

    /** @param blockNumber/blockHash populated for finalized SUCCESS/FAILED receipts. */
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
        if (chainQuarantine.findActive(chainConfigId).isPresent()) {
            throw new ChainQuarantinedException(chainConfigId);
        }
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
            long blockNumber = receipt.getBlockNumber().longValueExact();
            String blockHash = receipt.getBlockHash();
            if (blockHash == null || blockHash.isBlank()) {
                log.warn("Mined receipt for tx={} at block={} has no block hash; refusing to "
                        + "write chain-derived state until exact incarnation identity is available",
                        txHash, blockNumber);
                return VerdictResult.of(TxVerdict.PENDING);
            }
            boolean isFinal = finalityResolver.levelOf(chainConfig.getIdentifier(), web3j, blockNumber)
                    .atLeast(FinalityLevel.FINALIZED);
            if (!isFinal) {
                return VerdictResult.of(TxVerdict.PENDING);
            }
            TxVerdict verdict = "0x1".equals(receipt.getStatus()) ? TxVerdict.SUCCESS : TxVerdict.FAILED;
            return new VerdictResult(verdict, blockNumber, blockHash);
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
        if (verdict.blockNumber() == null || verdict.blockHash() == null || verdict.blockHash().isBlank()) {
            throw new IllegalStateException("A SUCCESS verdict requires exact block number and block hash identity");
        }
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                chainConfigId, verdict.blockNumber(), verdict.blockHash(), txHash,
                "orgidentity", effectType, entityType, entityId, null,
                CompensationCategory.INVERSE_FLIP));
    }
}
