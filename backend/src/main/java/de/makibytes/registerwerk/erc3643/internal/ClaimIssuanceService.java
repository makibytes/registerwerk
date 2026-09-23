package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.events.ClaimIssuedEvent;
import de.makibytes.registerwerk.erc3643.events.ClaimRevokedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.erc3643.internal.Erc3643DeploymentService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and revokes KYC/AML claims on ONCHAINID identity contracts.
 *
 * <p>Standard ERC-3643 T-REX claim topics:
 * <ul>
 *   <li>{@code 1} – KYC (Know Your Customer)</li>
 *   <li>{@code 2} – AML (Anti-Money Laundering)</li>
 *   <li>{@code 3} – ACCREDITATION (Qualified Investor)</li>
 * </ul>
 *
 * <p>Claims are signed by the registry backend wallet on behalf of the chain's ONCHAINID
 * {@code ClaimIssuer} contract ({@code registerwerk.contracts.claim-issuer.<chain>}, managed by that
 * wallet). That contract — not the wallet — must be a TrustedIssuer in the suite's
 * TrustedIssuersRegistry (and in the EcosystemTrustedIssuersRegistry for PermissionOracle) for
 * the relevant claim topics.
 */
@Service
@Transactional
public class ClaimIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(ClaimIssuanceService.class);

    /** ERC-3643 standard claim topic: Know Your Customer. */
    public static final long CLAIM_TOPIC_KYC = 1L;

    /** ERC-3643 standard claim topic: Anti-Money Laundering. */
    public static final long CLAIM_TOPIC_AML = 2L;

    /** ERC-3643 standard claim topic: Qualified Investor / Accreditation. */
    public static final long CLAIM_TOPIC_ACCREDITATION = 3L;

    private final OnchainIdentityRepository identityRepository;
    private final OnchainClaimRepository claimRepository;
    private final Erc3643DeploymentService deploymentService;
    private final ApplicationEventPublisher eventPublisher;

    public ClaimIssuanceService(
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository,
            Erc3643DeploymentService deploymentService,
            ApplicationEventPublisher eventPublisher) {
        this.identityRepository = identityRepository;
        this.claimRepository = claimRepository;
        this.deploymentService = deploymentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Issues a KYC claim (topic 1) to a legal entity's ONCHAINID on the specified chain.
     *
     * <p>The claim is signed by the registry backend wallet and submitted on-chain.
     * The resulting claim record is persisted to {@code onchain_claim}.
     *
     * @param legalEntityId ID of the legal entity to receive the claim
     * @param chainConfigId ID of the chain configuration
     * @param expiresAt     optional expiry; {@code null} means no expiry
     * @param actorId       ID of the user issuing the claim (for audit)
     * @param actorRole     role of the user issuing the claim (for audit)
     * @return the persisted {@link OnchainClaim}
     */
    public OnchainClaim issueKycClaim(UUID legalEntityId, UUID chainConfigId, Instant expiresAt,
                                      UUID actorId, String actorRole) {
        return issueClaim(legalEntityId, chainConfigId, CLAIM_TOPIC_KYC, "KYC", expiresAt, actorId, actorRole);
    }

    /**
     * Issues an AML claim (topic 2) to a legal entity's ONCHAINID on the specified chain.
     *
     * <p>AML claims do not carry an expiry; re-screening results in revoking the old claim
     * and issuing a new one.
     *
     * @param legalEntityId ID of the legal entity to receive the claim
     * @param chainConfigId ID of the chain configuration
     * @param actorId       ID of the user issuing the claim (for audit)
     * @param actorRole     role of the user issuing the claim (for audit)
     * @return the persisted {@link OnchainClaim}
     */
    public OnchainClaim issueAmlClaim(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole) {
        return issueClaim(legalEntityId, chainConfigId, CLAIM_TOPIC_AML, "AML", null, actorId, actorRole);
    }

    /**
     * Issues a claim for any topic to a legal entity's ONCHAINID on the specified chain.
     * Used for custom or non-standard claim topics beyond KYC (1) and AML (2).
     *
     * @param legalEntityId ID of the legal entity to receive the claim
     * @param chainConfigId ID of the chain configuration
     * @param topic         claim topic number
     * @param topicLabel    human-readable label (e.g. "ACCREDITATION")
     * @param expiresAt     optional expiry; {@code null} means no expiry
     * @param actorId       ID of the user issuing the claim (for audit)
     * @param actorRole     role of the user issuing the claim (for audit)
     * @return the persisted {@link OnchainClaim}
     */
    public OnchainClaim issueCustomClaim(
            UUID legalEntityId, UUID chainConfigId, long topic, String topicLabel, Instant expiresAt,
            UUID actorId, String actorRole) {
        return issueClaim(legalEntityId, chainConfigId, topic, topicLabel, expiresAt, actorId, actorRole);
    }

    private OnchainClaim issueClaim(
            UUID legalEntityId, UUID chainConfigId, long topic, String topicLabel, Instant expiresAt,
            UUID actorId, String actorRole) {
        log.info("Issuing claim (topic={}, label={}) for entity={} on chain={}",
            topic, topicLabel, legalEntityId, chainConfigId);

        OnchainIdentity identity = identityRepository
            .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
            .orElseThrow(() -> new EntityNotFoundException(
                "OnchainIdentity for entity " + legalEntityId + " on chain", chainConfigId));

        // Throws if the identity isn't deployed yet — nothing is persisted below unless the tx was
        // actually submitted. The claim starts confirmed=false: Erc3643ClaimConfirmationListener
        // flips it once addClaim reaches FINALIZED, and getActiveClaims won't count it until then.
        String txHash = deploymentService.issueKycClaim(identity.getId(), topic, expiresAt);

        OnchainClaim claim = buildClaimRecord(identity, chainConfigId, topic, topicLabel, expiresAt);
        claim.setTxHash(txHash);
        OnchainClaim saved = claimRepository.save(claim);

        eventPublisher.publishEvent(new ClaimIssuedEvent(saved.getId(), actorId, actorRole, java.util.Map.of()));

        return saved;
    }

    /**
     * Revokes a claim: submits {@code ONCHAINID.removeClaim} and the issuer-level
     * {@code ClaimIssuer.revokeClaimBySignature}, and records both intents.
     *
     * <p>After revocation the investor will fail the compliance check for this claim topic
     * and transfers will be blocked until a new valid claim is issued. Removal alone is
     * reversible (a CLAIM key on the identity can re-add the original signature); the issuer-level
     * step makes it final — see {@link Erc3643DeploymentService#revokeClaimAtIssuer}.
     *
     * <p>Idempotent: each step is skipped once its tx is submitted (or confirmed), and the issuer
     * step also reads {@code isClaimRevoked} on chain first. Calling this again on an
     * already-removed claim retries only a missing or failed issuer-level step. Emits
     * {@link ClaimRevokedEvent} whenever something was submitted.
     *
     * @param claimId ID of the {@link OnchainClaim} to revoke
     * @param actorId   ID of the user revoking the claim (for audit)
     * @param actorRole role of the user revoking the claim (for audit)
     */
    public void revokeClaim(UUID identityId, UUID claimId, UUID actorId, String actorRole) {
        OnchainClaim claim = claimRepository.findByIdAndOnchainIdentityId(claimId, identityId)
            .orElseThrow(() -> new EntityNotFoundException("OnchainClaim", claimId));
        revoke(claim, actorId, actorRole, Map.of());
    }

    /**
     * Revokes every confirmed KYC (1) / AML (2) claim of a legal entity on one chain, both at the
     * identity and at the issuer. Expired claims are included: expiry is not enforced on chain.
     *
     * @return claims not yet fully revoked on chain (issuance or a revocation step still
     *         unconfirmed, or an issuer-level step that threw) — the caller retries until this is 0
     * @throws RuntimeException if a {@code removeClaim} submission fails
     */
    public int revokeComplianceClaims(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole,
                                      Map<String, Object> auditDetails) {
        OnchainIdentity identity = identityRepository
            .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId).orElse(null);
        if (identity == null) {
            return 0;
        }
        int unresolved = 0;
        for (OnchainClaim claim : claimRepository.findByOnchainIdentityId(identity.getId())) {
            if (claim.getTopic() != CLAIM_TOPIC_KYC && claim.getTopic() != CLAIM_TOPIC_AML) {
                continue;
            }
            if (!claim.isConfirmed()) {
                if (claim.getTxHash() != null) {
                    // addClaim still in flight: removeClaim would revert on a missing claim; retry
                    // once it confirms (the issuer-level step alone would also do, but needs the
                    // same retry for the removal anyway).
                    unresolved++;
                }
                continue;
            }
            boolean issuerStepSettled = revoke(claim, actorId, actorRole, auditDetails);
            // Done only once both steps are confirmed on chain (or the issuer step is not applicable).
            boolean removalConfirmed = claim.getRevokedAt() != null;
            boolean issuerPending = claim.getIssuerRevocationTxHash() != null && claim.getIssuerRevokedAt() == null;
            if (!issuerStepSettled || !removalConfirmed || issuerPending) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /** @return whether the issuer-level step is done or not applicable (no retry needed). */
    private boolean revoke(OnchainClaim claim, UUID actorId, String actorRole, Map<String, Object> auditDetails) {
        UUID claimId = claim.getId();
        String removalTx = null;
        if (claim.getRevokedAt() == null && claim.getRevocationTxHash() == null) {
            // revokedAt remains chain-derived and is therefore only set after final confirmation.
            // revocationTxHash is the fail-closed intent marker: getActiveClaims excludes the claim as
            // soon as removeClaim is submitted, including while a reorged transaction awaits a new
            // canonical verdict. Only a confirmed failed receipt clears that marker.
            log.info("Revoking claim={}", claimId);
            removalTx = deploymentService.revokeKycClaim(claimId);
            claim.setRevocationTxHash(removalTx);
        }

        String issuerTx = null;
        boolean issuerStepSettled = true;
        if (claim.getIssuerRevocationTxHash() == null && claim.getIssuerRevokedAt() == null) {
            try {
                issuerTx = deploymentService.revokeClaimAtIssuer(claimId);
                claim.setIssuerRevocationTxHash(issuerTx);
            } catch (RuntimeException e) {
                // removeClaim (if any) is already submitted; keep it and let the caller retry the
                // issuer step — the removal alone already fails closed in getActiveClaims.
                issuerStepSettled = false;
                log.error("Issuer-level revocation of claim={} failed; the issuer still vouches for its "
                        + "signature until retried: {}", claimId, e.getMessage(), e);
            }
        }

        if (removalTx == null && issuerTx == null) {
            log.info("Claim={} needs no further revocation step (revokedAt={}, removalTx={}, issuerTx={})",
                    claimId, claim.getRevokedAt(), claim.getRevocationTxHash(), claim.getIssuerRevocationTxHash());
            return issuerStepSettled;
        }
        claimRepository.save(claim);

        Map<String, Object> details = new java.util.LinkedHashMap<>(auditDetails);
        if (removalTx != null) details.put("removeClaimTx", removalTx);
        if (issuerTx != null) details.put("revokeClaimBySignatureTx", issuerTx);
        eventPublisher.publishEvent(new ClaimRevokedEvent(claimId, actorId, actorRole, details));
        return issuerStepSettled;
    }

    /**
     * Returns all confirmed, non-revoked, non-expired claims for a legal entity on a specific
     * chain whose revocation has not been submitted.
     *
     * <p>A non-null {@link OnchainClaim#getRevocationTxHash()} excludes the claim immediately.
     * Revocation is a compliance-narrowing operation, so the safe state while its receipt is
     * pending — or while the same transaction is pending again after a reorg — is inactive. The
     * confirmation listener clears that intent marker only after a confirmed failed receipt,
     * which restores the claim without conflating submitted intent with chain-derived
     * {@link OnchainClaim#getRevokedAt()} state.
     *
     * @param legalEntityId ID of the legal entity
     * @param chainConfigId ID of the chain configuration
     * @return list of active claims (may be empty)
     */
    @Transactional(readOnly = true)
    public List<OnchainClaim> getActiveClaims(UUID legalEntityId, UUID chainConfigId) {
        OnchainIdentity identity = identityRepository
            .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
            .orElseThrow(() -> new EntityNotFoundException(
                "OnchainIdentity for entity " + legalEntityId + " on chain", chainConfigId));

        Instant now = Instant.now();
        return claimRepository.findByOnchainIdentityId(identity.getId()).stream()
            .filter(OnchainClaim::isConfirmed)
            .filter(c -> c.getRevokedAt() == null)
            .filter(c -> c.getRevocationTxHash() == null)
            .filter(c -> c.getExpiresAt() == null || c.getExpiresAt().isAfter(now))
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OnchainClaim buildClaimRecord(
            OnchainIdentity identity, UUID chainConfigId, long topic, String topicLabel, Instant expiresAt) {
        OnchainClaim claim = new OnchainClaim();
        claim.setOnchainIdentityId(identity.getId());
        claim.setTopic(topic);
        claim.setTopicLabel(topicLabel);
        claim.setIssuedAt(Instant.now());
        claim.setExpiresAt(expiresAt);

        // Sign the claim and store the proof so it can be submitted or verified on-chain.
        String identityAddress = identity.getIdentityAddress();
        if (identityAddress != null && !identityAddress.startsWith("0x-PENDING")) {
            try {
                // Same signer + ClaimIssuer contract as the submitted addClaim, so the stored issuer
                // and signature are the ones on chain (removeClaim / revokeClaimBySignature key on them).
                ClaimSigningService.SignedClaim signed =
                        deploymentService.signClaim(chainConfigId, identityAddress, topic, expiresAt);
                claim.setIssuerAddress(signed.issuerAddress());
                claim.setClaimData(signed.claimData());
                claim.setClaimSignature(signed.claimSignature());
            } catch (IllegalStateException e) {
                // Signing key not configured (dev/test without key) — record issuer as unknown.
                log.warn("ClaimSigningService not configured; claim will be stored without signature: {}", e.getMessage());
                claim.setIssuerAddress("0x-REGISTRY-WALLET");
            }
        } else {
            claim.setIssuerAddress("0x-REGISTRY-WALLET");
        }
        return claim;
    }
}
