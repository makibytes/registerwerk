package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.MintControlRule;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.MintControlRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages mint control rules that govern who can mint tokens and up to what limits.
 */
@Service
@Transactional
public class MintControlService {

    private static final Logger log = LoggerFactory.getLogger(MintControlService.class);

    private final MintControlRuleRepository mintControlRuleRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final BlockchainClientRegistry blockchainClientRegistry;

    public MintControlService(
            MintControlRuleRepository mintControlRuleRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            BlockchainClientRegistry blockchainClientRegistry) {
        this.mintControlRuleRepository = mintControlRuleRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.blockchainClientRegistry = blockchainClientRegistry;
    }

    /**
     * Creates a new mint control rule for the given deployment and persists it.
     *
     * @param deploymentId ID of the target deployment
     * @param rule         rule to create (assetDeploymentId will be set automatically)
     * @return saved rule
     */
    public MintControlRule createRule(UUID deploymentId, MintControlRule rule) {
        assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        rule.setAssetDeploymentId(deploymentId);
        rule.setActive(true);
        MintControlRule saved = mintControlRuleRepository.save(rule);
        log.info("Created MintControlRule: id={}, deployment={}", saved.getId(), deploymentId);
        return saved;
    }

    /**
     * Deactivates a rule (soft-delete).
     */
    public void deactivateRule(UUID ruleId) {
        MintControlRule rule = mintControlRuleRepository.findById(ruleId)
            .orElseThrow(() -> new EntityNotFoundException("MintControlRule", ruleId));
        rule.setActive(false);
        mintControlRuleRepository.save(rule);
        log.info("Deactivated MintControlRule: id={}", ruleId);
    }

    /**
     * Lists all active rules for a deployment.
     */
    @Transactional(readOnly = true)
    public List<MintControlRule> listRules(UUID deploymentId) {
        return mintControlRuleRepository.findByAssetDeploymentIdAndActive(deploymentId, true);
    }
}
