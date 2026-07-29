package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.internal.GasSponsorshipService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicyRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GasSponsorshipService unit tests")
class GasSponsorshipServiceTest {

    @Mock
    private GasSponsorshipPolicyRepository policyRepository;

    @Mock
    private AssetDeploymentRepository assetDeploymentRepository;

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private GasSponsorshipService gasSponsorshipService;

    private GasSponsorshipPolicy buildPolicy() {
        GasSponsorshipPolicy policy = new GasSponsorshipPolicy();
        policy.setSponsor(GasSponsorshipPolicy.Sponsor.ISSUER);
        policy.setMonthlyCapEth(BigDecimal.valueOf(0.5));
        return policy;
    }

    @Test
    @DisplayName("createForDeployment scopes the policy to the deployment, not an issuer")
    void createForDeployment_scopesToDeployment() {
        UUID deploymentId = UUID.randomUUID();
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);

        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GasSponsorshipPolicy saved = gasSponsorshipService.createForDeployment(deploymentId, buildPolicy());

        assertThat(saved.getAssetDeploymentId()).isEqualTo(deploymentId);
        assertThat(saved.getIssuerId()).isNull();
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    @DisplayName("createForDeployment throws when the deployment does not exist")
    void createForDeployment_throwsForUnknownDeployment() {
        UUID deploymentId = UUID.randomUUID();
        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gasSponsorshipService.createForDeployment(deploymentId, buildPolicy()))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("createIssuerDefault scopes the policy to the issuer, with no deployment")
    void createIssuerDefault_scopesToIssuer() {
        UUID issuerId = UUID.randomUUID();
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GasSponsorshipPolicy saved = gasSponsorshipService.createIssuerDefault(issuerId, buildPolicy());

        assertThat(saved.getIssuerId()).isEqualTo(issuerId);
        assertThat(saved.getAssetDeploymentId()).isNull();
    }

    @Test
    @DisplayName("resolveEffectivePolicy prefers a deployment-scoped override over the issuer default")
    void resolveEffectivePolicy_prefersDeploymentOverride() {
        UUID deploymentId = UUID.randomUUID();
        GasSponsorshipPolicy override = buildPolicy();
        override.setAssetDeploymentId(deploymentId);

        when(policyRepository.findByAssetDeploymentIdAndActive(deploymentId, true))
            .thenReturn(Optional.of(override));

        Optional<GasSponsorshipPolicy> resolved = gasSponsorshipService.resolveEffectivePolicy(deploymentId);

        assertThat(resolved).contains(override);
    }

    @Test
    @DisplayName("resolveEffectivePolicy falls back to the issuer's default when there is no override")
    void resolveEffectivePolicy_fallsBackToIssuerDefault() {
        UUID deploymentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();

        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setIssuerId(issuerId);
        GasSponsorshipPolicy issuerDefault = buildPolicy();
        issuerDefault.setIssuerId(issuerId);

        when(policyRepository.findByAssetDeploymentIdAndActive(deploymentId, true)).thenReturn(Optional.empty());
        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(policyRepository.findByIssuerIdAndAssetDeploymentIdIsNullAndActive(issuerId, true))
            .thenReturn(Optional.of(issuerDefault));

        Optional<GasSponsorshipPolicy> resolved = gasSponsorshipService.resolveEffectivePolicy(deploymentId);

        assertThat(resolved).contains(issuerDefault);
    }

    @Test
    @DisplayName("resolveEffectivePolicy returns empty when neither an override nor a default exists")
    void resolveEffectivePolicy_returnsEmptyWhenUnconfigured() {
        UUID deploymentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();

        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setIssuerId(issuerId);

        when(policyRepository.findByAssetDeploymentIdAndActive(deploymentId, true)).thenReturn(Optional.empty());
        when(assetDeploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(policyRepository.findByIssuerIdAndAssetDeploymentIdIsNullAndActive(issuerId, true))
            .thenReturn(Optional.empty());

        assertThat(gasSponsorshipService.resolveEffectivePolicy(deploymentId)).isEmpty();
    }

    @Test
    @DisplayName("deactivate sets active to false")
    void deactivate_setsActiveFalse() {
        UUID policyId = UUID.randomUUID();
        GasSponsorshipPolicy policy = buildPolicy();
        policy.setId(policyId);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        gasSponsorshipService.deactivate(policyId);

        assertThat(policy.getActive()).isFalse();
        verify(policyRepository).save(policy);
    }
}
