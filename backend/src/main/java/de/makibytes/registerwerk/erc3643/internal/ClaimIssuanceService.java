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
 * <p>The registry backend wallet must be registered as a TrustedIssuer in the suite's
 * TrustedIssuersRegistry for the relevant claim topics before claims can be issued on-chain.
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
    private final ClaimSigningService claimSigningService;
    private final ApplicationEventPublisher eventPublisher;

    public ClaimIssuanceService(
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository,
            Erc3643DeploymentService deploymentService,
            ClaimSigningService claimSigningService,
            ApplicationEventPublisher eventPublisher) {
        this.identityRepository = identityRepository;
        this.claimRepository = claimRepository;
        this.deploymentService = deploymentService;
        this.claimSigningService = claimSigningService;
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

        deploymentService.issueKycClaim(identity.getId(), topic, expiresAt);

        OnchainClaim claim = buildClaimRecord(identity, chainConfigId, topic, topicLabel, expiresAt);
        OnchainClaim saved = claimRepository.save(claim);

        eventPublisher.publishEvent(new ClaimIssuedEvent(saved.getId(), actorId, actorRole, java.util.Map.of()));

        return saved;
    }

    /**
     * Revokes a claim: marks it as revoked in the database and submits an on-chain revocation.
     *
     * <p>After revocation the investor will fail the compliance check for this claim topic
     * and transfers will be blocked until a new valid claim is issued.
     *
     * @param claimId ID of the {@link OnchainClaim} to revoke
     * @param actorId   ID of the user revoking the claim (for audit)
     * @param actorRole role of the user revoking the claim (for audit)
     */
    public void revokeClaim(UUID identityId, UUID claimId, UUID actorId, String actorRole) {
        log.info("Revoking claim={}", claimId);

        OnchainClaim claim = claimRepository.findByIdAndOnchainIdentityId(claimId, identityId)
            .orElseThrow(() -> new EntityNotFoundException("OnchainClaim", claimId));

        if (claim.getRevokedAt() != null) {
            log.warn("Claim={} is already revoked at {}. Skipping.", claimId, claim.getRevokedAt());
            return;
        }

        deploymentService.revokeKycClaim(claimId);

        claim.setRevokedAt(Instant.now());
        claimRepository.save(claim);

        eventPublisher.publishEvent(new ClaimRevokedEvent(claimId, actorId, actorRole, java.util.Map.of()));
    }

    /**
     * Returns all non-revoked, non-expired claims for a legal entity on a specific chain.
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
            .filter(c -> c.getRevokedAt() == null)
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
                ClaimSigningService.SignedClaim signed =
                        claimSigningService.signClaim(chainConfigId, identityAddress, topic, expiresAt);
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
