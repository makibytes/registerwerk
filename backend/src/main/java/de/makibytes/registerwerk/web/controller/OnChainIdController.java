package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.erc3643.ClaimIssuanceService;
import de.makibytes.registerwerk.application.erc3643.OnChainIdService;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.chain.ChainConfig;
import de.makibytes.registerwerk.domain.erc3643.OnchainClaim;
import de.makibytes.registerwerk.domain.erc3643.OnchainIdentity;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.ChainConfigRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.BlockchainTransactionRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OnchainClaimRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OnchainIdentityRepository;
import de.makibytes.registerwerk.domain.blockchain.BlockchainTransaction;
import de.makibytes.registerwerk.web.dto.AsyncDataStatus;
import de.makibytes.registerwerk.web.dto.erc3643.ClaimInfo;
import de.makibytes.registerwerk.web.dto.erc3643.OnchainIdentityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
    private final BlockchainTransactionRepository blockchainTransactionRepository;
    private final OnchainIdentityRepository identityRepository;
    private final OnchainClaimRepository claimRepository;

    public OnChainIdController(
            OnChainIdService onChainIdService,
            ClaimIssuanceService claimIssuanceService,
            ChainConfigRepository chainConfigRepository,
            BlockchainTransactionRepository blockchainTransactionRepository,
            OnchainIdentityRepository identityRepository,
            OnchainClaimRepository claimRepository) {
        this.onChainIdService = onChainIdService;
        this.claimIssuanceService = claimIssuanceService;
        this.chainConfigRepository = chainConfigRepository;
        this.blockchainTransactionRepository = blockchainTransactionRepository;
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
            @RequestBody Map<String, String> body) {
        String chainConfigIdStr = body.get("chainConfigId");
        if (chainConfigIdStr == null || chainConfigIdStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID chainConfigId = UUID.fromString(chainConfigIdStr);
        log.info("POST deploy-identity for entityId={} on chainConfigId={}", entityId, chainConfigId);

        OnchainIdentity identity = onChainIdService.getOrCreate(entityId, chainConfigId);

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
        List<OnchainClaim> claims = claimRepository.findByOnchainIdentityId(identityId);
        return ResponseEntity.ok(claims.stream().map(this::toClaimInfo).toList());
    }

    /**
     * Issues a KYC claim (topic 1) to an ONCHAINID identity.
     * Optionally accepts an {@code expiresAt} ISO-8601 timestamp in the request body.
     */
    @PostMapping("/{identityId}/claims/kyc")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<ClaimInfo> issueKycClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @RequestBody(required = false) Map<String, String> body) {
        log.info("POST KYC claim for entityId={} identityId={}", entityId, identityId);

        OnchainIdentity identity = requireIdentity(identityId);
        Instant expiresAt = parseExpiresAt(body);

        OnchainClaim claim = claimIssuanceService.issueKycClaim(
            entityId, identity.getChainConfigId(), expiresAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Issues an AML claim (topic 2) to an ONCHAINID identity.
     */
    @PostMapping("/{identityId}/claims/aml")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<ClaimInfo> issueAmlClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId) {
        log.info("POST AML claim for entityId={} identityId={}", entityId, identityId);

        OnchainIdentity identity = requireIdentity(identityId);
        OnchainClaim claim = claimIssuanceService.issueAmlClaim(entityId, identity.getChainConfigId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Issues a custom claim (any topic) to an ONCHAINID identity.
     * Accepts {@code topic} (long), {@code topicLabel} (string), and optionally {@code expiresAt}.
     */
    @PostMapping("/{identityId}/claims/custom")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<ClaimInfo> issueCustomClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @RequestBody Map<String, String> body) {
        log.info("POST custom claim for entityId={} identityId={}", entityId, identityId);
        OnchainIdentity identity = requireIdentity(identityId);
        long topic = Long.parseLong(body.getOrDefault("topic", "0"));
        String topicLabel = body.getOrDefault("topicLabel", "TOPIC_" + topic);
        Instant expiresAt = parseExpiresAt(body);
        OnchainClaim claim = claimIssuanceService.issueCustomClaim(
            entityId, identity.getChainConfigId(), topic, topicLabel, expiresAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(toClaimInfo(claim));
    }

    /**
     * Revokes a specific claim by ID.
     */
    @DeleteMapping("/{identityId}/claims/{claimId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> revokeClaim(
            @PathVariable UUID entityId,
            @PathVariable UUID identityId,
            @PathVariable UUID claimId) {
        log.info("DELETE (revoke) claim={} on identityId={}", claimId, identityId);
        claimIssuanceService.revokeClaim(claimId);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OnchainIdentity requireIdentity(UUID identityId) {
        return identityRepository.findById(identityId)
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
        if (txHash == null || txHash.isBlank()) {
            return false;
        }
        return blockchainTransactionRepository.findByTxHash(txHash)
            .map(tx -> tx.getStatus() == BlockchainTransaction.Status.PENDING)
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

    private Instant parseExpiresAt(Map<String, String> body) {
        if (body == null) {
            return null;
        }
        String expiresAtStr = body.get("expiresAt");
        if (expiresAtStr == null || expiresAtStr.isBlank()) {
            return null;
        }
        return Instant.parse(expiresAtStr);
    }
}
