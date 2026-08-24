package de.makibytes.registerwerk.erc3643.web;

import de.makibytes.registerwerk.erc3643.internal.ClaimIssuanceService;
import de.makibytes.registerwerk.erc3643.internal.OnChainIdService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.shared.api.AsyncDataStatus;
import de.makibytes.registerwerk.erc3643.web.dto.ClaimInfo;
import de.makibytes.registerwerk.erc3643.web.dto.OnchainIdentityResponse;
import de.makibytes.registerwerk.erc3643.web.dto.ClaimExpiryRequest;
import de.makibytes.registerwerk.erc3643.web.dto.CustomClaimRequest;
import de.makibytes.registerwerk.erc3643.web.dto.DeployIdentityRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for ONCHAINID identity management scoped to a specific legal entity.
 *
 * <p>Base path: {@code /api/v1/entities/{entityId}/onchain-identity}
 */
@RestController
@RequestMapping("/api/v1/entities/{entityId}/onchain-identity")
public class OnChainIdController {

    private static final Logger log = LoggerFactory.getLogger(OnChainIdController.class);

    private final OnChainIdService onChainIdService;
    private final ClaimIssuanceService claimIssuanceService;
    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainApi blockchainApi;
    private final OnchainIdentityRepository identityRepository;
    private final OnchainClaimRepository claimRepository;

    public OnChainIdController(
            OnChainIdService onChainIdService,
            ClaimIssuanceService claimIssuanceService,
            ChainConfigRepository chainConfigRepository,
            BlockchainApi blockchainApi,
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository) {
        this.onChainIdService = onChainIdService;
        this.claimIssuanceService = claimIssuanceService;
        this.chainConfigRepository = chainConfigRepository;
        this.blockchainApi = blockchainApi;
        this.identityRepository = identityRepository;
        this.claimRepository = claimRepository;
    }

    // ── Identities ────────────────────────────────────────────────────────────

    /**
     * Returns all ONCHAINID identities deployed for a legal entity across all chains.
     */
    @GetMapping
    @PreAuthorize(
        "hasRole('REGISTRY_ADMIN') " +
        "or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<List<OnchainIdentityResponse>> getIdentities(@PathVariable UUID entityId) {
        log.debug("GET onchain-identities for entityId={}", entityId);
        List<OnchainIdentity> identities = onChainIdService.getIdentities(entityId);
        List<OnchainIdentityResponse> responses = identities.stream()
            .map(id -> toResponse(id, claimIssuanceService.getActiveClaims(entityId, id.getChainConfigId())))
            .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Deploys a new ONCHAINID proxy for the entity on the specified chain.
     * If an identity already exists for that chain, the existing record is returned.
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<OnchainIdentityResponse> deployIdentity(
            @PathVariable UUID entityId,
            @RequestBody @Valid DeployIdentityRequest body,
            Authentication auth) {
        UUID chainConfigId = body.chainConfigId();
        log.info("POST deploy-identity for entityId={} on chainConfigId={}", entityId, chainConfigId);

        OnchainIdentity identity = onChainIdService.getOrCreate(
                entityId, chainConfigId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));

        List<OnchainClaim> activeClaims = claimIssuanceService.getActiveClaims(entityId, chainConfigId);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(toResponse(identity, activeClaims));
    }

    // ── Claims ────────────────────────────────────────────────────────────────

    /**
     * Returns all claims (active, expired, revoked) issued to a specific ONCHAINID identity.
     */
    @GetMapping("/{identityId}/claims")
    @PreAuthorize(
        "hasRole('REGISTRY_ADMIN') " +
        "or @entityOwnershipChecker.isOwner(#entityId, authentication)")
    public ResponseEntity<List<ClaimInfo>> getClaims(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId) {
        log.debug("GET claims for identityId={}", identityId);
        requireIdentity(entityId, identityId);
        List<OnchainClaim> claims = claimRepository.findByOnchainIdentityId(identityId);
        return ResponseEntity.ok(claims.stream().map(this::toClaimInfo).toList());
    }

    /**
     * Issues a KYC claim (topic 1) to an ONCHAINID identity.
     * Optionally accepts an {@code expiresAt} ISO-8601 timestamp in the request body.
     */
    @PostMapping("/{identityId}/claims/kyc")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "CLAIM_ISSUANCE")
    public ResponseEntity<ClaimInfo> issueKycClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @RequestBody(required = false) @Valid ClaimExpiryRequest body,
            Authentication auth) {
        log.info("POST KYC claim for entityId={} identityId={}", entityId, identityId);

        OnchainIdentity identity = requireIdentity(entityId, identityId);
        Instant expiresAt = body != null ? body.expiresAt() : null;

        OnchainClaim claim = claimIssuanceService.issueKycClaim(
            entityId, identity.getChainConfigId(), expiresAt,
            actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Issues an AML claim (topic 2) to an ONCHAINID identity.
     */
    @PostMapping("/{identityId}/claims/aml")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "CLAIM_ISSUANCE")
    public ResponseEntity<ClaimInfo> issueAmlClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            Authentication auth) {
        log.info("POST AML claim for entityId={} identityId={}", entityId, identityId);

        OnchainIdentity identity = requireIdentity(entityId, identityId);
        OnchainClaim claim = claimIssuanceService.issueAmlClaim(entityId, identity.getChainConfigId(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Issues a custom claim (any topic) to an ONCHAINID identity.
     * Accepts {@code topic} (long), {@code topicLabel} (string), and optionally {@code expiresAt}.
     */
    @PostMapping("/{identityId}/claims/custom")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "CLAIM_ISSUANCE")
    public ResponseEntity<ClaimInfo> issueCustomClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @RequestBody @Valid CustomClaimRequest body,
            Authentication auth) {
        log.info("POST custom claim for entityId={} identityId={}", entityId, identityId);
        OnchainIdentity identity = requireIdentity(entityId, identityId);
        OnchainClaim claim = claimIssuanceService.issueCustomClaim(
            entityId, identity.getChainConfigId(), body.topic(), body.topicLabel().trim(), body.expiresAt(),
            actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Revokes a specific claim by ID.
     */
    @DeleteMapping("/{identityId}/claims/{claimId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "CLAIM_REVOCATION")
    public ResponseEntity<Void> revokeClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @PathVariable UUID claimId,
            Authentication auth) {
        log.info("DELETE (revoke) claim={} on identityId={}", claimId, identityId);
        requireIdentity(entityId, identityId);
        claimIssuanceService.revokeClaim(identityId, claimId, actorId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static UUID actorId(Authentication auth) {
        return SecurityUtils.extractUserId(auth);
    }

    private OnchainIdentity requireIdentity(UUID entityId, UUID identityId) {
        return identityRepository.findByIdAndLegalEntityId(identityId, entityId)
            .orElseThrow(() -> new EntityNotFoundException("OnchainIdentity", identityId));
    }

    private OnchainIdentityResponse toResponse(OnchainIdentity identity, List<OnchainClaim> claims) {
        String chainIdentifier = chainConfigRepository.findById(identity.getChainConfigId())
            .map(ChainConfig::getIdentifier)
            .orElse("UNKNOWN_CHAIN");

        List<ClaimInfo> claimInfos = claims.stream().map(this::toClaimInfo).toList();

        return new OnchainIdentityResponse(
            identity.getId(),
            identity.getLegalEntityId(),
            chainIdentifier,
            identity.getIdentityAddress(),
            identity.getDeployedByTx(),
            resolveSyncStatus(identity),
            identity.getDeployedAt(),
            claimInfos
        );
    }

    private AsyncDataStatus resolveSyncStatus(OnchainIdentity identity) {
        if (identity.getIdentityAddress() == null || identity.getIdentityAddress().startsWith("0x-PENDING")) {
            return AsyncDataStatus.PENDING;
        }
        if (isTransactionPending(identity.getDeployedByTx())) {
            return AsyncDataStatus.UPDATING;
        }
        return AsyncDataStatus.READY;
    }

    private boolean isTransactionPending(String txHash) {
        if (txHash == null || txHash.isBlank()) return false;
        return blockchainApi.findByTxHash(txHash)
                .map(tx -> "PENDING".equals(tx.status()))
                .orElse(true);
    }

    private ClaimInfo toClaimInfo(OnchainClaim claim) {
        return new ClaimInfo(
            claim.getTopic(),
            claim.getTopicLabel(),
            claim.getIssuerAddress(),
            claim.getIssuedAt(),
            claim.getExpiresAt(),
            claim.getRevokedAt() != null,
            claim.getClaimData(),
            claim.getClaimSignature()
        );
    }

}
