package de.makibytes.registerwerk.erc3643.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopic;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuer;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.erc3643.internal.Erc3643LifecycleService;
import de.makibytes.registerwerk.erc3643.internal.IdentityRegistryService;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.AsyncDataStatus;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.blockchain.web.dto.FreezePartialRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.erc3643.web.dto.AddClaimTopicRequest;
import de.makibytes.registerwerk.erc3643.web.dto.AddTrustedIssuerRequest;
import de.makibytes.registerwerk.erc3643.web.dto.ComplianceStatusResponse;
import de.makibytes.registerwerk.erc3643.web.dto.Erc3643SuiteResponse;
import de.makibytes.registerwerk.erc3643.web.dto.IdentityRegistryEntryResponse;
import de.makibytes.registerwerk.erc3643.web.dto.RegisterInvestorRequest;
import de.makibytes.registerwerk.erc3643.web.dto.Erc3643AgentRequests;
import jakarta.validation.Valid;

/**
 * REST controller for ERC-3643 (T-REX) suite management operations scoped to a specific asset.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/erc3643}
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/erc3643")
public class Erc3643Controller {

    private static final Logger log = LoggerFactory.getLogger(Erc3643Controller.class);

    private final Erc3643LifecycleService lifecycleService;
    private final IdentityRegistryService identityRegistryService;
    private final OnchainIdentityRepository identityRepo;
    private final LegalEntityRepository entityRepo;
    private final Erc3643TrustedIssuerRepository trustedIssuerRepo;
    private final Erc3643ClaimTopicRepository claimTopicRepo;
    private final BlockchainApi blockchainApi;

    public Erc3643Controller(Erc3643LifecycleService lifecycleService,
                             IdentityRegistryService identityRegistryService,
                             OnchainIdentityRepository identityRepo,
                             LegalEntityRepository entityRepo,
                             Erc3643TrustedIssuerRepository trustedIssuerRepo,
                             Erc3643ClaimTopicRepository claimTopicRepo,
                             BlockchainApi blockchainApi) {
        this.lifecycleService = lifecycleService;
        this.identityRegistryService = identityRegistryService;
        this.identityRepo = identityRepo;
        this.entityRepo = entityRepo;
        this.trustedIssuerRepo = trustedIssuerRepo;
        this.claimTopicRepo = claimTopicRepo;
        this.blockchainApi = blockchainApi;
    }

    // ── Suite ─────────────────────────────────────────────────────────────────

    /**
     * Returns the T-REX suite details for a specific asset deployment.
     */
    @GetMapping("/{deploymentId}/suite")
    @PreAuthorize(
        "hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') " +
        "or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<Erc3643SuiteResponse> getSuiteDetails(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId) {
        log.debug("GET suite for assetId={} deploymentId={}", assetId, deploymentId);
        Erc3643Suite suite = lifecycleService.getSuiteDetails(assetId, deploymentId);
        return ResponseEntity.ok(toSuiteResponse(suite));
    }

    // ── Compliance modules ────────────────────────────────────────────────────

    /**
     * Adds a compliance module to the suite's ModularCompliance contract.
     */
    @PostMapping("/{deploymentId}/compliance-modules")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> addComplianceModule(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.ComplianceModule body,
            Authentication auth) {
        log.info("POST compliance-module for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        lifecycleService.addComplianceModule(suiteId, body.moduleAddress(), body.moduleType(), body.parameters(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Removes a compliance module from the suite's ModularCompliance contract.
     */
    @DeleteMapping("/{deploymentId}/compliance-modules/{moduleId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> removeComplianceModule(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @PathVariable UUID moduleId,
            Authentication auth) {
        log.info("DELETE compliance-module={} for deploymentId={}", moduleId, deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        lifecycleService.removeComplianceModule(suiteId, moduleId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    // ── Trusted issuers ───────────────────────────────────────────────────────

    /**
     * Lists all active trusted claim issuers for the suite.
     */
    @GetMapping("/{deploymentId}/trusted-issuers")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<List<TrustedIssuerResponse>> listTrustedIssuers(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId) {
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        List<TrustedIssuerResponse> result = trustedIssuerRepo.findBySuiteIdAndRemovedAtIsNull(suiteId)
                .stream()
                .map(this::toTrustedIssuerResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Registers a trusted claim issuer on the suite's TrustedIssuersRegistry.
     */
    @PostMapping("/{deploymentId}/trusted-issuers")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> addTrustedIssuer(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody @Valid AddTrustedIssuerRequest request,
            Authentication auth) {
        log.info("POST trusted-issuer for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        lifecycleService.addTrustedIssuer(suiteId, request.issuerAddress(), request.claimTopics(), request.legalEntityId(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Removes a trusted issuer from the suite's TrustedIssuersRegistry.
     */
    @DeleteMapping("/{deploymentId}/trusted-issuers/{issuerId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> removeTrustedIssuer(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @PathVariable UUID issuerId,
            Authentication auth) {
        log.info("DELETE trusted-issuer={} for deploymentId={}", issuerId, deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        lifecycleService.removeTrustedIssuer(suiteId, issuerId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    // ── Claim topics ──────────────────────────────────────────────────────────

    /**
     * Lists all required claim topics for the suite.
     */
    @GetMapping("/{deploymentId}/claim-topics")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<List<ClaimTopicResponse>> listClaimTopics(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId) {
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        List<ClaimTopicResponse> result = claimTopicRepo.findBySuiteId(suiteId)
                .stream()
                .map(this::toClaimTopicResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Registers a required claim topic on the suite's ClaimTopicsRegistry.
     */
    @PostMapping("/{deploymentId}/claim-topics")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> addClaimTopic(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody @Valid AddClaimTopicRequest request,
            Authentication auth) {
        log.info("POST claim-topic for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        String label = (request.label() != null) ? request.label() : "TOPIC_" + request.topic();
        lifecycleService.addRequiredClaimTopic(suiteId, request.topic(), label, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── Identity Registry ─────────────────────────────────────────────────────

    /**
     * Lists all currently registered investors in the suite's IdentityRegistry.
     */
    @GetMapping("/{deploymentId}/identity-registry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<java.util.List<IdentityRegistryEntryResponse>> listIdentityRegistry(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            Authentication authentication) {
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        var entries = identityRegistryService.getRegisteredInvestors(suiteId);
        return ResponseEntity.ok(entries.stream()
                .map(entry -> toRegistryEntryResponse(entry, authentication))
                .toList());
    }

    /**
     * Registers an investor wallet in the suite's IdentityRegistry.
     * Creates the investor's ONCHAINID on the target chain if it does not exist yet.
     */
    @PostMapping("/{deploymentId}/identity-registry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "IDENTITY_REGISTRATION")
    public ResponseEntity<IdentityRegistryEntryResponse> registerInvestor(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody @Valid RegisterInvestorRequest request,
            Authentication auth) {
        log.info("POST identity-registry for deploymentId={} wallet={}", deploymentId, request.walletAddress());
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        var entry = identityRegistryService.registerInvestor(
                suiteId, request.walletAddress(), request.legalEntityId(),
                request.chainConfigId(), request.countryCode(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toRegistryEntryResponse(entry, null));
    }

    /**
     * Removes an investor from the suite's IdentityRegistry (soft-delete).
     */
    @DeleteMapping("/{deploymentId}/identity-registry/{registryEntryId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "IDENTITY_REGISTRATION")
    public ResponseEntity<Void> removeInvestor(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @PathVariable UUID registryEntryId,
            Authentication auth) {
        log.info("DELETE identity-registry entry={} for deploymentId={}", registryEntryId, deploymentId);
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        identityRegistryService.removeInvestor(suiteId, registryEntryId,
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the aggregated compliance state for a suite: investor count vs limits,
     * blocked countries, active compliance modules.
     */
    @GetMapping("/{deploymentId}/compliance-status")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<ComplianceStatusResponse> getComplianceStatus(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId) {
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        return ResponseEntity.ok(lifecycleService.getComplianceStatus(suiteId));
    }

    // ── Agent operations ──────────────────────────────────────────────────────

    @PostMapping("/{deploymentId}/forced-transfer")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication)")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.ForcedTransfer body, Authentication auth) {
        log.info("POST forced-transfer on deploymentId={} by actor={}", deploymentId, actorName(auth));
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        UUID txId = lifecycleService.forcedTransfer(suiteId, body.from(), body.to(),
                body.amount(), body.reason().trim(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return accepted(txId);
    }

    @PostMapping("/{deploymentId}/forced-approve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication)")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_APPROVE_OVERRIDE")
    public ResponseEntity<TxSubmissionResponse> forcedApprove(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.ForcedApprove body, Authentication auth) {
        log.info("POST forced-approve on deploymentId={} by actor={}", deploymentId, actorName(auth));
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        UUID txId = lifecycleService.forcedApprove(suiteId, body.owner(), body.spender(),
                body.amount(), body.reason().trim(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return accepted(txId);
    }

    @PostMapping("/{deploymentId}/freeze")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> freezeAddress(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.Address body, Authentication auth) {
        log.info("POST freeze on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.freezeAddress(resolveSuiteId(assetId, deploymentId), body.address(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/unfreeze")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> unfreezeAddress(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.Address body, Authentication auth) {
        log.info("POST unfreeze on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.unfreezeAddress(resolveSuiteId(assetId, deploymentId), body.address(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/freeze-partial")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> freezePartialTokens(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid FreezePartialRequest request, Authentication auth) {
        log.info("POST freeze-partial address={} amount={} on deploymentId={} by actor={}",
                request.address(), request.amount(), deploymentId, actorName(auth));
        return accepted(lifecycleService.freezePartialTokens(
                resolveSuiteId(assetId, deploymentId), request.address(), request.amount(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/unfreeze-partial")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> unfreezePartialTokens(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid FreezePartialRequest request, Authentication auth) {
        log.info("POST unfreeze-partial address={} amount={} on deploymentId={} by actor={}",
                request.address(), request.amount(), deploymentId, actorName(auth));
        return accepted(lifecycleService.unfreezePartialTokens(
                resolveSuiteId(assetId, deploymentId), request.address(), request.amount(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/pause")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> pause(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId, Authentication auth) {
        log.info("POST pause on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.pause(resolveSuiteId(assetId, deploymentId), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/unpause")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> unpause(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId, Authentication auth) {
        log.info("POST unpause on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.unpause(resolveSuiteId(assetId, deploymentId), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/force-burn")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication)")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> forceBurn(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.ForceBurn body, Authentication auth) {
        log.info("POST force-burn on deploymentId={} by actor={}", deploymentId, actorName(auth));
        UUID suiteId = resolveSuiteId(assetId, deploymentId);
        UUID txId = lifecycleService.forceBurn(suiteId, body.from(),
                body.amount(), body.legalBasis().trim(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return accepted(txId);
    }

    @PostMapping("/{deploymentId}/batch-forced-transfer")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication)")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> batchForcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.BatchTransfer body, Authentication auth) {
        log.info("POST batch-forced-transfer on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.batchForcedTransfer(resolveSuiteId(assetId, deploymentId),
                body.froms(), body.tos(), body.amounts(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/batch-mint")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<TxSubmissionResponse> batchMint(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.BatchAmounts body, Authentication auth) {
        log.info("POST batch-mint on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.batchMint(resolveSuiteId(assetId, deploymentId), body.addresses(), body.amounts(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{deploymentId}/batch-burn")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication)")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> batchBurn(
            @PathVariable UUID assetId, @PathVariable UUID deploymentId,
            @RequestBody @Valid Erc3643AgentRequests.BatchAmounts body, Authentication auth) {
        log.info("POST batch-burn on deploymentId={} by actor={}", deploymentId, actorName(auth));
        return accepted(lifecycleService.batchBurn(resolveSuiteId(assetId, deploymentId), body.addresses(), body.amounts(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID resolveSuiteId(UUID assetId, UUID deploymentId) {
        return lifecycleService.getSuiteDetails(assetId, deploymentId).getId();
    }

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    private static UUID actorId(Authentication auth) {
        return SecurityUtils.extractUserId(auth);
    }

    private TrustedIssuerResponse toTrustedIssuerResponse(Erc3643TrustedIssuer issuer) {
        return new TrustedIssuerResponse(
            issuer.getId(),
            issuer.getSuiteId(),
            issuer.getIssuerAddress(),
            issuer.getClaimTopics(),
            issuer.getLegalEntityId(),
            issuer.getAddedAt(),
            issuer.getRemovedAt()
        );
    }

    private ClaimTopicResponse toClaimTopicResponse(Erc3643ClaimTopic topic) {
        return new ClaimTopicResponse(
            topic.getId(),
            topic.getSuiteId(),
            topic.getTopic(),
            topic.getLabel()
        );
    }

    // ── Response records ──────────────────────────────────────────────────────

    public record TrustedIssuerResponse(
        UUID id,
        UUID suiteId,
        String issuerAddress,
        List<Long> claimTopics,
        UUID legalEntityId,
        Instant addedAt,
        Instant removedAt
    ) {}

    public record ClaimTopicResponse(
        UUID id,
        UUID suiteId,
        long topic,
        String label
    ) {}

    private Erc3643SuiteResponse toSuiteResponse(Erc3643Suite suite) {
        return new Erc3643SuiteResponse(
            suite.getId(),
            suite.getAssetDeploymentId(),
            suite.getTokenAddress(),
            suite.getIdentityRegistryAddress(),
            suite.getIdentityRegistryStorage(),
            suite.getComplianceAddress(),
            suite.getClaimTopicsRegistry(),
            suite.getTrustedIssuersRegistry(),
            suite.getIsConfidential(),
            suite.getCreatedAt()
        );
    }

    private IdentityRegistryEntryResponse toRegistryEntryResponse(
            Erc3643IdentityRegistry entry,
            Authentication authentication) {
        var identity = identityRepo.findById(entry.getOnchainIdentityId()).orElse(null);
        String identityAddress = identity != null ? identity.getIdentityAddress() : null;
        UUID legalEntityId = identity != null ? identity.getLegalEntityId() : null;
        String entityName = legalEntityId != null
                ? entityRepo.findById(legalEntityId).map(e -> e.getCurrentName()).orElse(null)
                : null;
        boolean verified = identityRegistryService.isVerified(entry.getSuiteId(), entry.getWalletAddress());
        return new IdentityRegistryEntryResponse(
            entry.getId(), entry.getSuiteId(), entry.getWalletAddress(),
            entry.getOnchainIdentityId(), identityAddress, legalEntityId, entityName,
            entry.getCountryCode(), entry.getRegisteredAt(), entry.getRegisteredByTx(),
            resolveSyncStatus(entry, identityAddress), entry.isActive(), verified,
            null,
            null);
    }

    private AsyncDataStatus resolveSyncStatus(Erc3643IdentityRegistry entry, String identityAddress) {
        if (identityAddress == null || identityAddress.startsWith("0x-PENDING")) {
            return AsyncDataStatus.PENDING;
        }
        if (isTransactionPending(entry.getRegisteredByTx())) {
            return AsyncDataStatus.PENDING;
        }
        return AsyncDataStatus.READY;
    }

    private boolean isTransactionPending(String txHash) {
        if (txHash == null || txHash.isBlank()) return false;
        return blockchainApi.findByTxHash(txHash)
                .map(tx -> "PENDING".equals(tx.status()))
                .orElse(true);
    }
}
