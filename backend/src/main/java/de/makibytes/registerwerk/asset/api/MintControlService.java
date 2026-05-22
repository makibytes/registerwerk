package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public MintControlService(
            MintControlRuleRepository mintControlRuleRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            BlockchainClientRegistry blockchainClientRegistry) {
        this.mintControlRuleRepository = mintControlRuleRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.blockchainClientRegistry = blockchainClientRegistry;
    }

    public MintControlRule createRule(UUID deploymentId, MintControlRule rule) {
        assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        rule.setAssetDeploymentId(deploymentId);
        rule.setActive(true);
        MintControlRule saved = mintControlRuleRepository.save(rule);
        log.info("Created MintControlRule: id={}, deployment={}", saved.getId(), deploymentId);
        return saved;
    }

    public void deactivateRule(UUID ruleId) {
        MintControlRule rule = mintControlRuleRepository.findById(ruleId)
            .orElseThrow(() -> new EntityNotFoundException("MintControlRule", ruleId));
        rule.setActive(false);
        mintControlRuleRepository.save(rule);
        log.info("Deactivated MintControlRule: id={}", ruleId);
    }

    @Transactional(readOnly = true)
    public List<MintControlRule> listRules(UUID deploymentId) {
        return mintControlRuleRepository.findByAssetDeploymentIdAndActive(deploymentId, true);
    }
}
