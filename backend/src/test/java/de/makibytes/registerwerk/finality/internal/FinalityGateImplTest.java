package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.api.FinalityPolicyService;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinalityGateImpl — allow/block decisions and require()'s throw behavior")
class FinalityGateImplTest {

    @Mock private FinalityPolicyService policyService;
    @Mock private ChainEffectRepository chainEffectRepository;

    private FinalityGateImpl gate;
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gate = new FinalityGateImpl(policyService, chainEffectRepository);
        // Both stubbed leniently: nullAssetId_skipsUnresolvedCompensationCheck calls check() with a
        // different (null) assetId, so neither this policyService stub nor the chainEffectRepository
        // one below is exercised by that test — lenient() avoids Mockito flagging them unnecessary.
        lenient().when(policyService.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20))
                .thenReturn(FinalityLevel.FINALIZED);
        lenient().when(chainEffectRepository.existsByAssetIdAndStatusInAndAcknowledgedAtIsNull(eq(assetId), any()))
                .thenReturn(false);
    }

    @Test
    @DisplayName("currentLevel at or above required is Allowed")
    void atOrAboveRequired_isAllowed() {
        FinalityDecision decision = gate.check(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.FINALIZED);

        assertThat(decision).isInstanceOf(FinalityDecision.Allowed.class);
        assertThat(((FinalityDecision.Allowed) decision).currentLevel()).isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("currentLevel below required is Blocked with reason BELOW_REQUIRED")
    void belowRequired_isBlocked() {
        FinalityDecision decision = gate.check(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.SAFE);

        assertThat(decision).isInstanceOf(FinalityDecision.Blocked.class);
        FinalityDecision.Blocked blocked = (FinalityDecision.Blocked) decision;
        assertThat(blocked.reason()).isEqualTo(FinalityDecision.Blocked.Reason.BELOW_REQUIRED);
        assertThat(blocked.requiredLevel()).isEqualTo(FinalityLevel.FINALIZED);
        assertThat(blocked.currentLevel()).isEqualTo(FinalityLevel.SAFE);
    }

    @Test
    @DisplayName("ORPHANED currentLevel is always Blocked with reason ORPHANED, regardless of required level")
    void orphaned_isBlockedWithDistinctReason() {
        FinalityDecision decision = gate.check(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.ORPHANED);

        assertThat(decision).isInstanceOf(FinalityDecision.Blocked.class);
        assertThat(((FinalityDecision.Blocked) decision).reason()).isEqualTo(FinalityDecision.Blocked.Reason.ORPHANED);
    }

    @Test
    @DisplayName("an unresolved compensation on the asset blocks with reason UNRESOLVED_COMPENSATION, "
            + "even though currentLevel already satisfies the required level")
    void unresolvedCompensation_blocksRegardlessOfLevel() {
        when(chainEffectRepository.existsByAssetIdAndStatusInAndAcknowledgedAtIsNull(eq(assetId), any()))
                .thenReturn(true);

        FinalityDecision decision = gate.check(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.FINALIZED);

        assertThat(decision).isInstanceOf(FinalityDecision.Blocked.class);
        FinalityDecision.Blocked blocked = (FinalityDecision.Blocked) decision;
        assertThat(blocked.reason()).isEqualTo(FinalityDecision.Blocked.Reason.UNRESOLVED_COMPENSATION);
        assertThat(blocked.currentLevel()).isEqualTo(FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("a null assetId skips the unresolved-compensation check entirely")
    void nullAssetId_skipsUnresolvedCompensationCheck() {
        when(policyService.requiredLevel(GatedOperation.AUTHORITATIVE_BALANCE, null, TokenStandard.ERC20))
                .thenReturn(FinalityLevel.FINALIZED);

        FinalityDecision decision = gate.check(GatedOperation.AUTHORITATIVE_BALANCE, null, TokenStandard.ERC20, FinalityLevel.FINALIZED);

        assertThat(decision).isInstanceOf(FinalityDecision.Allowed.class);
    }

    @Test
    @DisplayName("require() is a no-op when allowed")
    void require_allowed_doesNotThrow() {
        assertThatCode(() -> gate.require(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.FINALIZED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("require() throws FinalityNotReachedException carrying the blocked decision")
    void require_blocked_throwsWithDecision() {
        assertThatThrownBy(() -> gate.require(GatedOperation.AUTHORITATIVE_BALANCE, assetId, TokenStandard.ERC20, FinalityLevel.PROVISIONAL))
                .isInstanceOf(FinalityNotReachedException.class)
                .satisfies(e -> {
                    FinalityDecision.Blocked decision = ((FinalityNotReachedException) e).decision();
                    assertThat(decision.currentLevel()).isEqualTo(FinalityLevel.PROVISIONAL);
                    assertThat(decision.operation()).isEqualTo(GatedOperation.AUTHORITATIVE_BALANCE);
                });
    }
}
