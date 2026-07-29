package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModuleRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643TrustedIssuerRepository;
import de.makibytes.registerwerk.erc3643.internal.Erc3643LifecycleService;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Erc3643LifecycleService unit tests")
class Erc3643LifecycleServiceTest {

    @Mock private Erc3643SuiteRepository suiteRepository;
    @Mock private Erc3643ComplianceModuleRepository complianceModuleRepository;
    @Mock private Erc3643TrustedIssuerRepository trustedIssuerRepository;
    @Mock private Erc3643ClaimTopicRepository claimTopicRepository;
    @Mock private Erc3643IdentityRegistryRepository identityRegistryRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private BlockchainTransactionService txService;
    @Mock private HolderBlockGate holderBlockGate;

    @InjectMocks
    private Erc3643LifecycleService service;

    @Test
    @DisplayName("getSuiteDetails(assetId, deploymentId) rejects a deployment belonging to a different asset")
    void getSuiteDetails_rejectsCrossTenantDeployment() {
        UUID deploymentId = UUID.randomUUID();
        UUID ownAssetId = UUID.randomUUID();
        UUID otherTenantAssetId = UUID.randomUUID();

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(otherTenantAssetId);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.getSuiteDetails(ownAssetId, deploymentId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("getSuiteDetails(assetId, deploymentId) succeeds when the deployment belongs to the asset")
    void getSuiteDetails_succeedsForMatchingAsset() {
        UUID deploymentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(assetId);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setAssetDeploymentId(deploymentId);
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.of(suite));

        Erc3643Suite result = service.getSuiteDetails(assetId, deploymentId);

        assertThat(result.getId()).isEqualTo(suiteId);
    }

    @Test
    @DisplayName("getSuiteDetails(assetId, deploymentId) 404s (not a bare lookup failure) when the deployment itself doesn't exist")
    void getSuiteDetails_deploymentNotFound() {
        UUID deploymentId = UUID.randomUUID();
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSuiteDetails(UUID.randomUUID(), deploymentId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("addTrustedIssuer throws (not silently no-ops) when the suite's trustedIssuersRegistry address is unset")
    void addTrustedIssuer_throwsOnMissingContractAddress() {
        UUID suiteId = UUID.randomUUID();
        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        suite.setTrustedIssuersRegistry("0x-PENDING-SOMETHING");
        when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));

        assertThatThrownBy(() -> service.addTrustedIssuer(
                suiteId, "0x1111111111111111111111111111111111111111", List.of(1L),
                UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── §16 eWpG Sperrvermerk enforcement ────────────────────────────────────

    @Test
    @DisplayName("forcedTransfer refuses when the source wallet has an active holder block")
    void forcedTransfer_refusesBlockedSourceWallet() {
        String from = "0x1111111111111111111111111111111111111111";
        String to = "0x2222222222222222222222222222222222222222";
        when(holderBlockGate.isBlocked(null, from)).thenReturn(true);

        assertThatThrownBy(() -> service.forcedTransfer(
                UUID.randomUUID(), from, to, new java.math.BigDecimal("10"), "Court order",
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sperrvermerk");
    }

    @Test
    @DisplayName("batchMint refuses when any target wallet has an active holder block")
    void batchMint_refusesBlockedTargetWallet() {
        String blocked = "0x3333333333333333333333333333333333333333";
        when(holderBlockGate.isBlocked(null, blocked)).thenReturn(true);

        assertThatThrownBy(() -> service.batchMint(
                UUID.randomUUID(), List.of(blocked), List.of(new java.math.BigDecimal("5")),
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sperrvermerk");
    }
}
