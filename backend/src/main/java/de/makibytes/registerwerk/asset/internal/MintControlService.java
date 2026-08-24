package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.MintControlRuleCreatedEvent;
import de.makibytes.registerwerk.asset.events.MintControlRuleDeactivatedEvent;
import de.makibytes.registerwerk.asset.events.MintControlRuleUpdatedEvent;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.MintControlRule;
import de.makibytes.registerwerk.deployment.api.MintControlRuleRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages mint control rules. Moved from blockchain/api to asset/api to break the
 * asset ↔ blockchain modulith cycle (Track F). Uses asset repositories directly and
 * BlockchainClientRegistry (asset → blockchain is allowed).
 */
@Service
@Transactional
public class MintControlService {

    private static final Logger log = LoggerFactory.getLogger(MintControlService.class);

    private final MintControlRuleRepository mintControlRuleRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public MintControlService(
            MintControlRuleRepository mintControlRuleRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            BlockchainClientRegistry blockchainClientRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.mintControlRuleRepository = mintControlRuleRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.eventPublisher = eventPublisher;
    }

    public MintControlRule createRule(UUID assetId, UUID deploymentId, MintControlRule rule,
                                      UUID actorId, String actorRole) {
        requireDeployment(assetId, deploymentId);
        validateRule(rule, false);
        rule.setAssetDeploymentId(deploymentId);
        rule.setActive(true);
        MintControlRule saved = mintControlRuleRepository.save(rule);
        log.info("Created MintControlRule: id={}, deployment={}", saved.getId(), deploymentId);
        eventPublisher.publishEvent(new MintControlRuleCreatedEvent(saved.getId(), deploymentId, actorId, actorRole,
                saved.getTargetAddress(), saved.getRuleType() != null ? saved.getRuleType().name() : null,
                saved.getMaxAmount()));
        return saved;
    }

    /**
     * Patches a mint control rule's target address, type, or max amount.
     * Null fields in the patch are ignored (partial update semantics).
     */
    public MintControlRule updateRule(UUID deploymentId, UUID ruleId, MintControlRule patch,
                                      UUID actorId, String actorRole) {
        validateRule(patch, true);
        MintControlRule rule = mintControlRuleRepository.findByIdAndAssetDeploymentId(ruleId, deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("MintControlRule", ruleId));
        if (patch.getTargetAddress() != null) rule.setTargetAddress(patch.getTargetAddress());
        if (patch.getRuleType()      != null) rule.setRuleType(patch.getRuleType());
        if (patch.getMaxAmount()     != null) rule.setMaxAmount(patch.getMaxAmount());
        MintControlRule saved = mintControlRuleRepository.save(rule);
        log.info("Updated MintControlRule: id={}", ruleId);
        eventPublisher.publishEvent(new MintControlRuleUpdatedEvent(saved.getId(), saved.getAssetDeploymentId(),
                actorId, actorRole, saved.getTargetAddress(),
                saved.getRuleType() != null ? saved.getRuleType().name() : null, saved.getMaxAmount()));
        return saved;
    }

    public void deactivateRule(UUID deploymentId, UUID ruleId, UUID actorId, String actorRole) {
        MintControlRule rule = mintControlRuleRepository.findByIdAndAssetDeploymentId(ruleId, deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("MintControlRule", ruleId));
        rule.setActive(false);
        mintControlRuleRepository.save(rule);
        log.info("Deactivated MintControlRule: id={}", ruleId);
        eventPublisher.publishEvent(new MintControlRuleDeactivatedEvent(ruleId, rule.getAssetDeploymentId(),
                actorId, actorRole));
    }

    @Transactional(readOnly = true)
    public List<MintControlRule> listRules(UUID assetId, UUID deploymentId) {
        requireDeployment(assetId, deploymentId);
        return mintControlRuleRepository.findByAssetDeploymentIdAndActive(deploymentId, true);
    }

    @Transactional(readOnly = true)
    public void requireDeployment(UUID assetId, UUID deploymentId) {
        assetDeploymentRepository.findByIdAndAssetId(deploymentId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private static void validateRule(MintControlRule rule, boolean patch) {
        if (!patch && (rule.getTargetAddress() == null || rule.getTargetAddress().isBlank())) {
            throw new IllegalArgumentException("targetAddress is required");
        }
        if (!patch && rule.getRuleType() == null) {
            throw new IllegalArgumentException("ruleType is required");
        }
        if (rule.getTargetAddress() != null && rule.getTargetAddress().isBlank()) {
            throw new IllegalArgumentException("targetAddress must not be blank");
        }
        if (rule.getMaxAmount() != null && rule.getMaxAmount().signum() <= 0) {
            throw new IllegalArgumentException("maxAmount must be greater than zero");
        }
    }
}
