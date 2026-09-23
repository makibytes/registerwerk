package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.GasSponsorshipPolicyDeactivatedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicyRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages gas-sponsorship policies: a per-deployment override, or an issuer-level default
 * that future deployments from that issuer inherit until they get their own override.
 */
@Service
@Transactional
public class GasSponsorshipService {

    private static final Logger log = LoggerFactory.getLogger(GasSponsorshipService.class);

    private final GasSponsorshipPolicyRepository policyRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GasSponsorshipService(
            GasSponsorshipPolicyRepository policyRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher) {
        this.policyRepository = policyRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
    }

    public GasSponsorshipPolicy createForDeployment(UUID deploymentId, GasSponsorshipPolicy policy) {
        assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        policy.setAssetDeploymentId(deploymentId);
        policy.setIssuerId(null);
        policy.setActive(true);
        GasSponsorshipPolicy saved = policyRepository.save(policy);
        log.info("Created deployment-scoped GasSponsorshipPolicy: id={}, deployment={}", saved.getId(), deploymentId);
        return saved;
    }

    public GasSponsorshipPolicy createIssuerDefault(UUID issuerId, GasSponsorshipPolicy policy) {
        policy.setIssuerId(issuerId);
        policy.setAssetDeploymentId(null);
        policy.setActive(true);
        GasSponsorshipPolicy saved = policyRepository.save(policy);
        log.info("Created issuer-default GasSponsorshipPolicy: id={}, issuer={}", saved.getId(), issuerId);
        return saved;
    }

    /**
     * Deactivates a policy. The voucher issuer refuses it immediately; the published event is the
     * trigger for the funder/operator to also call {@code EwpgPaymaster.setPolicyActive(false)}
     * on chain (the contract has its own flag since review phase 2, T2-02).
     */
    public void deactivate(UUID policyId, UUID actorId, String actorRole) {
        GasSponsorshipPolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> new EntityNotFoundException("GasSponsorshipPolicy", policyId));
        policy.setActive(false);
        policyRepository.save(policy);
        log.info("Deactivated GasSponsorshipPolicy: id={}", policyId);
        eventPublisher.publishEvent(new GasSponsorshipPolicyDeactivatedEvent(
            policyId, policy.getAssetDeploymentId(), policy.getIssuerId(), actorId, actorRole));
    }

    @Transactional(readOnly = true)
    public List<GasSponsorshipPolicy> listForIssuer(UUID issuerId) {
        return policyRepository.findByIssuerId(issuerId);
    }

    /**
     * Resolves the policy that actually applies to a deployment: its own override if one is
     * active, otherwise its issuer's active default, otherwise empty (no sponsorship).
     */
    @Transactional(readOnly = true)
    public Optional<GasSponsorshipPolicy> resolveEffectivePolicy(UUID deploymentId) {
        Optional<GasSponsorshipPolicy> override = policyRepository.findByAssetDeploymentIdAndActive(deploymentId, true);
        if (override.isPresent()) {
            return override;
        }
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        UUID issuerId = assetRepository.findById(deployment.getAssetId())
            .orElseThrow(() -> new EntityNotFoundException("Asset", deployment.getAssetId()))
            .getIssuerId();
        return policyRepository.findByIssuerIdAndAssetDeploymentIdIsNullAndActive(issuerId, true);
    }
}
