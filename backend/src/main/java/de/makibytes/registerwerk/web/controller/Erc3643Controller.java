package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.erc3643.Erc3643LifecycleService;
import de.makibytes.registerwerk.application.erc3643.IdentityRegistryService;
import de.makibytes.registerwerk.domain.erc3643.Erc3643ClaimTopic;
import de.makibytes.registerwerk.domain.erc3643.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.domain.erc3643.Erc3643Suite;
import de.makibytes.registerwerk.domain.erc3643.Erc3643TrustedIssuer;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OnchainIdentityRepository;
import de.makibytes.registerwerk.web.dto.erc3643.AddClaimTopicRequest;
import de.makibytes.registerwerk.web.dto.erc3643.AddTrustedIssuerRequest;
import de.makibytes.registerwerk.web.dto.erc3643.ComplianceStatusResponse;
import de.makibytes.registerwerk.web.dto.erc3643.Erc3643SuiteResponse;
import de.makibytes.registerwerk.web.dto.erc3643.IdentityRegistryEntryResponse;
import de.makibytes.registerwerk.web.dto.erc3643.RegisterInvestorRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public Erc3643Controller(Erc3643LifecycleService lifecycleService,
                             IdentityRegistryService identityRegistryService,
                             OnchainIdentityRepository identityRepo,
                             LegalEntityRepository entityRepo,
                             Erc3643TrustedIssuerRepository trustedIssuerRepo,
                             Erc3643ClaimTopicRepository claimTopicRepo) {
        this.lifecycleService = lifecycleService;
        this.identityRegistryService = identityRegistryService;
        this.identityRepo = identityRepo;
        this.entityRepo = entityRepo;
        this.trustedIssuerRepo = trustedIssuerRepo;
        this.claimTopicRepo = claimTopicRepo;
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
        Erc3643Suite suite = lifecycleService.getSuiteDetails(deploymentId);
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
            @RequestBody Map<String, Object> body) {
        log.info("POST compliance-module for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        String moduleAddress = (String) body.get("moduleAddress");
        String moduleType = (String) body.get("moduleType");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) body.getOrDefault("parameters", Map.of());
        lifecycleService.addComplianceModule(suiteId, moduleAddress, moduleType, parameters);
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
            @PathVariable UUID moduleId) {
        log.info("DELETE compliance-module={} for deploymentId={}", moduleId, deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        lifecycleService.removeComplianceModule(suiteId, moduleId);
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
        UUID suiteId = resolveSuiteId(deploymentId);
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
            @RequestBody @Valid AddTrustedIssuerRequest request) {
        log.info("POST trusted-issuer for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        lifecycleService.addTrustedIssuer(suiteId, request.issuerAddress(), request.claimTopics(), request.legalEntityId());
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
            @PathVariable UUID issuerId) {
        log.info("DELETE trusted-issuer={} for deploymentId={}", issuerId, deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        lifecycleService.removeTrustedIssuer(suiteId, issuerId);
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
        UUID suiteId = resolveSuiteId(deploymentId);
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
            @RequestBody @Valid AddClaimTopicRequest request) {
        log.info("POST claim-topic for deploymentId={}", deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        String label = (request.label() != null) ? request.label() : "TOPIC_" + request.topic();
        lifecycleService.addRequiredClaimTopic(suiteId, request.topic(), label);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── Identity Registry ─────────────────────────────────────────────────────

    /**
     * Lists all currently registered investors in the suite's IdentityRegistry.
     */
    @GetMapping("/{deploymentId}/identity-registry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<java.util.List<IdentityRegistryEntryResponse>> listIdentityRegistry(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId) {
        UUID suiteId = resolveSuiteId(deploymentId);
        var entries = identityRegistryService.getRegisteredInvestors(suiteId);
        return ResponseEntity.ok(entries.stream().map(this::toRegistryEntryResponse).toList());
    }

    /**
     * Registers an investor wallet in the suite's IdentityRegistry.
     * Creates the investor's ONCHAINID on the target chain if it does not exist yet.
     */
    @PostMapping("/{deploymentId}/identity-registry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<IdentityRegistryEntryResponse> registerInvestor(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody @Valid RegisterInvestorRequest request) {
        log.info("POST identity-registry for deploymentId={} wallet={}", deploymentId, request.walletAddress());
        UUID suiteId = resolveSuiteId(deploymentId);
        var entry = identityRegistryService.registerInvestor(
                suiteId, request.walletAddress(), request.legalEntityId(),
                request.chainConfigId(), request.countryCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(toRegistryEntryResponse(entry));
    }

    /**
     * Removes an investor from the suite's IdentityRegistry (soft-delete).
     */
    @DeleteMapping("/{deploymentId}/identity-registry/{registryEntryId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> removeInvestor(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @PathVariable UUID registryEntryId) {
        log.info("DELETE identity-registry entry={} for deploymentId={}", registryEntryId, deploymentId);
        UUID suiteId = resolveSuiteId(deploymentId);
        identityRegistryService.removeInvestor(suiteId, registryEntryId);
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
        UUID suiteId = resolveSuiteId(deploymentId);
        return ResponseEntity.ok(lifecycleService.getComplianceStatus(suiteId));
    }

    // ── Agent operations ──────────────────────────────────────────────────────

    /**
     * Executes a forced token transfer (agent-only regulatory operation).
     */
    @PostMapping("/{deploymentId}/forced-transfer")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> forcedTransfer(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        log.info("POST forced-transfer on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        String from = body.get("from");
        String to = body.get("to");
        BigDecimal amount = new BigDecimal(body.get("amount"));
        String reason = body.getOrDefault("reason", "");
        lifecycleService.forcedTransfer(suiteId, from, to, amount, reason);
        return ResponseEntity.accepted().build();
    }

    /**
     * Freezes an investor address on the token contract (agent-only).
     * Legal basis: AWG §17 (sanctions); GwG §40 (AML freeze); MiCAR Art. 36.
     */
    @PostMapping("/{deploymentId}/freeze")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> freezeAddress(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        log.info("POST freeze on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        String address = body.get("address");
        lifecycleService.freezeAddress(suiteId, address);
        return ResponseEntity.accepted().build();
    }

    /**
     * Unfreezes a previously frozen investor address.
     */
    @PostMapping("/{deploymentId}/unfreeze")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> unfreezeAddress(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        log.info("POST unfreeze on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        String address = body.get("address");
        lifecycleService.unfreezeAddress(suiteId, address);
        return ResponseEntity.accepted().build();
    }

    /**
     * Suspends all transfers on the T-REX token contract.
     * Legal basis: MiCAR Art. 36, 84; eWpG §24.
     */
    @PostMapping("/{deploymentId}/pause")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> pause(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            Authentication auth) {
        log.info("POST pause on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        lifecycleService.pause(suiteId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Resumes transfers on the T-REX token contract.
     */
    @PostMapping("/{deploymentId}/unpause")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> unpause(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            Authentication auth) {
        log.info("POST unpause on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        lifecycleService.unpause(suiteId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Burns tokens from an investor address (eWpG §26 Einziehung / compulsory cancellation).
     * Legal basis: eWpG §26 Einziehung; MiCAR Art. 94.
     */
    @PostMapping("/{deploymentId}/force-burn")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> forceBurn(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        log.info("POST force-burn on deploymentId={} by actor={}", deploymentId,
            auth != null ? auth.getName() : "unknown");
        UUID suiteId = resolveSuiteId(deploymentId);
        String from = body.get("from");
        java.math.BigDecimal amount = new java.math.BigDecimal(body.get("amount"));
        String legalBasis = body.getOrDefault("legalBasis", "");
        lifecycleService.forceBurn(suiteId, from, amount, legalBasis);
        return ResponseEntity.accepted().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the suite ID from the asset deployment ID.
     * The {@link Erc3643LifecycleService#getSuiteDetails(UUID)} call will throw
     * {@code EntityNotFoundException} if no suite exists for the deployment.
     */
    private UUID resolveSuiteId(UUID deploymentId) {
        return lifecycleService.getSuiteDetails(deploymentId).getId();
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

    private IdentityRegistryEntryResponse toRegistryEntryResponse(Erc3643IdentityRegistry entry) {
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
            entry.isActive(), verified);
    }
}
