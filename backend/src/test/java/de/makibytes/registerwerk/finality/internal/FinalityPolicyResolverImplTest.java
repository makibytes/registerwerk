package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinalityPolicyResolverImpl — resolution chain and hard-floor clamping")
class FinalityPolicyResolverImplTest {

    @Mock private FinalityPolicyAssignmentRepository assignmentRepository;
    @Mock private FinalityPolicyOverrideRepository overrideRepository;

    private FinalityPolicyResolverImpl resolver;
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new FinalityPolicyResolverImpl(assignmentRepository, overrideRepository);
        lenient().when(overrideRepository.findByAssetIdAndOperation(any(), any())).thenReturn(Optional.empty());
        lenient().when(assignmentRepository.findByScopeTypeAndAssetId(any(), any())).thenReturn(Optional.empty());
        lenient().when(assignmentRepository.findByScopeTypeAndTokenStandard(any(), any())).thenReturn(Optional.empty());
        lenient().when(assignmentRepository.findByScopeType(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("with nothing configured, falls back to the compiled-in BALANCED default (FINALIZED)")
    void noConfiguration_fallsBackToBalancedDefault() {
        assertThat(resolver.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("a hard-floor operation always resolves FINALIZED regardless of profile")
    void hardFloorOperation_alwaysFinalized() {
        assignGlobal(FinalityPolicyProfile.FAST);

        assertThat(resolver.requiredLevel(GatedOperation.REGISTER_STATEMENT_ISSUE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("FAST profile resolves a non-floored operation to SAFE")
    void fastProfile_nonFlooredOperation_resolvesSafe() {
        assignGlobal(FinalityPolicyProfile.FAST);

        assertThat(resolver.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("asset-scoped assignment beats global assignment")
    void assetAssignment_beatsGlobal() {
        assignGlobal(FinalityPolicyProfile.BALANCED);
        FinalityPolicyAssignment assetAssignment = new FinalityPolicyAssignment();
        assetAssignment.setScopeType(FinalityPolicyAssignment.ScopeType.ASSET);
        assetAssignment.setProfile(FinalityPolicyProfile.FAST);
        when(assignmentRepository.findByScopeTypeAndAssetId(FinalityPolicyAssignment.ScopeType.ASSET, assetId))
                .thenReturn(Optional.of(assetAssignment));

        assertThat(resolver.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("token-standard-scoped assignment beats global but loses to an asset-scoped one")
    void tokenStandardAssignment_beatsGlobalLosesToAsset() {
        assignGlobal(FinalityPolicyProfile.BALANCED);
        FinalityPolicyAssignment standardAssignment = new FinalityPolicyAssignment();
        standardAssignment.setScopeType(FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD);
        standardAssignment.setProfile(FinalityPolicyProfile.FAST);
        when(assignmentRepository.findByScopeTypeAndTokenStandard(FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD, TokenStandard.ERC20))
                .thenReturn(Optional.of(standardAssignment));

        assertThat(resolver.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("an asset override wins over every assignment, but is still clamped by a hard floor")
    void override_winsOverAssignments_stillClampedByHardFloor() {
        assignGlobal(FinalityPolicyProfile.FAST);
        FinalityPolicyOverride override = new FinalityPolicyOverride();
        override.setRequiredLevel(FinalityLevel.PROVISIONAL);
        when(overrideRepository.findByAssetIdAndOperation(assetId, GatedOperation.REGISTER_STATEMENT_ISSUE.name()))
                .thenReturn(Optional.of(override));

        // The override tries to lower a hard-floor operation to PROVISIONAL — must still clamp to FINALIZED.
        assertThat(resolver.requiredLevel(GatedOperation.REGISTER_STATEMENT_ISSUE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("an asset override on a non-floored operation is honored exactly as configured")
    void override_nonFlooredOperation_honoredExactly() {
        FinalityPolicyOverride override = new FinalityPolicyOverride();
        override.setRequiredLevel(FinalityLevel.PROVISIONAL);
        when(overrideRepository.findByAssetIdAndOperation(assetId, GatedOperation.AUTHORITATIVE_BALANCE.name()))
                .thenReturn(Optional.of(override));

        assertThat(resolver.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .isEqualTo(FinalityLevel.PROVISIONAL);
    }

    private void assignGlobal(FinalityPolicyProfile profile) {
        FinalityPolicyAssignment global = new FinalityPolicyAssignment();
        global.setScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL);
        global.setProfile(profile);
        // lenient: some callers stub this only to prove a more specific rung (asset/token-standard/
        // override) wins and short-circuits before this lookup is ever reached.
        lenient().when(assignmentRepository.findByScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL))
                .thenReturn(Optional.of(global));
    }
}
