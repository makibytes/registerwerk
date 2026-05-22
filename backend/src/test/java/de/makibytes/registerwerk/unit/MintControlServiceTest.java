package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.asset.internal.MintControlService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.MintControlRule;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.MintControlRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MintControlService unit tests")
class MintControlServiceTest {

    @Mock
    private MintControlRuleRepository mintControlRuleRepository;

    @Mock
    private AssetDeploymentRepository assetDeploymentRepository;

    @Mock
    private BlockchainClientRegistry blockchainClientRegistry;

    @InjectMocks
    private MintControlService mintControlService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MintControlRule buildRule(UUID deploymentId) {
        MintControlRule rule = new MintControlRule();
        rule.setTargetAddress("0xABCDEF1234567890abcdef1234567890abcdef12");
        rule.setRuleType(MintControlRule.RuleType.MINT_ALLOWANCE);
        rule.setMaxAmount(BigDecimal.valueOf(1_000_000));
        return rule;
    }

    private AssetDeployment buildDeployment(UUID id) {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(id);
        return deployment;
    }

    // ── createRule ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createRule should save the rule with active set to true")
    void createRule_shouldSaveRuleWithActiveTrue() {
        UUID deploymentId = UUID.randomUUID();
        MintControlRule rule = buildRule(deploymentId);

        when(assetDeploymentRepository.findById(deploymentId))
            .thenReturn(Optional.of(buildDeployment(deploymentId)));
        when(mintControlRuleRepository.save(any())).thenAnswer(inv -> {
            MintControlRule r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        MintControlRule saved = mintControlService.createRule(deploymentId, rule);

        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getAssetDeploymentId()).isEqualTo(deploymentId);
        verify(mintControlRuleRepository).save(rule);
    }

    // ── deactivateRule ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivateRule should set active to false on the found rule")
    void deactivateRule_shouldSetActiveFalse() {
        UUID ruleId = UUID.randomUUID();
        MintControlRule rule = buildRule(UUID.randomUUID());
        rule.setId(ruleId);
        rule.setActive(true);

        when(mintControlRuleRepository.findById(ruleId)).thenReturn(Optional.of(rule));
        when(mintControlRuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mintControlService.deactivateRule(ruleId);

        assertThat(rule.getActive()).isFalse();
        verify(mintControlRuleRepository).save(rule);
    }

    // ── listRules ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listRules should return only active rules for the given deployment")
    void listRules_shouldReturnOnlyActiveRules() {
        UUID deploymentId = UUID.randomUUID();

        MintControlRule activeRule = buildRule(deploymentId);
        activeRule.setId(UUID.randomUUID());
        activeRule.setActive(true);
        activeRule.setAssetDeploymentId(deploymentId);

        when(mintControlRuleRepository.findByAssetDeploymentIdAndActive(deploymentId, true))
            .thenReturn(List.of(activeRule));

        List<MintControlRule> rules = mintControlService.listRules(deploymentId);

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getActive()).isTrue();
    }
}
