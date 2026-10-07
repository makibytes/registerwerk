package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.web.AssetController;
import de.makibytes.registerwerk.asset.web.Erc3525SlotController;
import de.makibytes.registerwerk.blockchain.web.IssuerTokenController;
import de.makibytes.registerwerk.blockchain.web.TokenAdminController;
import de.makibytes.registerwerk.erc3643.web.Erc3643Controller;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review phase 3, K1 — the method-security contract of the endpoints T3-01/T3-16/T3-21 changed.
 * Every assertion here failed before the fix: the issuer could reach {@code /redeem}; issuer
 * {@code /burn} needed no grant and no 4-eyes; the five unfreeze endpoints and
 * {@code confidential-remove-viewer} were single-operator.
 */
@DisplayName("K1 authorization contract — redeem / burn / unfreeze / remove-viewer")
class LegalBlockAuthorizationContractTest {

    private static Method post(Class<?> controller, String path) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(controller.getSimpleName() + " has no POST " + path));
    }

    static Stream<Arguments> unfreezeEndpoints() {
        return Stream.of(
                Arguments.of(TokenAdminController.class, "/unfreeze"),
                Arguments.of(TokenAdminController.class, "/confidential-unfreeze"),
                Arguments.of(TokenAdminController.class, "/unfreeze-holding"),
                Arguments.of(Erc3643Controller.class, "/{deploymentId}/unfreeze"),
                Arguments.of(Erc3643Controller.class, "/{deploymentId}/unfreeze-partial"));
    }

    @ParameterizedTest(name = "{0} POST {1}")
    @MethodSource("unfreezeEndpoints")
    @DisplayName("every manual unfreeze requires step-up + a second approver (T3-16)")
    void unfreezeRequiresSecondApprover(Class<?> controller, String path) {
        RequiresStepUp stepUp = post(controller, path).getAnnotation(RequiresStepUp.class);
        assertThat(stepUp).isNotNull();
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("UNFREEZE");
    }

    @Test
    @DisplayName("removeViewerRequiresSecondApprover (T3-21)")
    void removeViewerRequiresSecondApprover() {
        RequiresStepUp stepUp = post(TokenAdminController.class, "/confidential-remove-viewer")
                .getAnnotation(RequiresStepUp.class);
        assertThat(stepUp.requireSecondApprover()).isTrue();
    }

    @Test
    @DisplayName("issuer loses /redeem: REGISTRY_ADMIN only, step-up + 4-eyes (T3-01)")
    void redeemIsOperatorOnlyWithSecondApprover() {
        Method redeem = post(AssetController.class, "/{id}/redeem");
        assertThat(redeem.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('REGISTRY_ADMIN')");
        RequiresStepUp stepUp = redeem.getAnnotation(RequiresStepUp.class);
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("ASSET_REDEMPTION");
    }

    @Test
    @DisplayName("issuer /burn needs an ASSET_TOKEN_ADMIN grant (not mere ownership) + 4-eyes (T3-01)")
    void issuerBurnNeedsGrantAndSecondApprover() {
        Method burn = post(IssuerTokenController.class, "/burn");
        String spel = burn.getAnnotation(PreAuthorize.class).value();
        assertThat(spel).contains("canForceAdmin").doesNotContain("canActAsIssuer");
        RequiresStepUp stepUp = burn.getAnnotation(RequiresStepUp.class);
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("ISSUER_BURN_EWG26");
    }

    @Test
    @DisplayName("setSupplyCapRequiresSecondApprover: the on-chain ceiling is no weaker than the 4-eyes issueSize amendment (B-12)")
    void setSupplyCapRequiresSecondApprover() {
        RequiresStepUp stepUp = post(TokenAdminController.class, "/set-supply-cap").getAnnotation(RequiresStepUp.class);
        assertThat(stepUp).isNotNull();
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("SUPPLY_CAP_CHANGE_MICAR46");
    }

    @ParameterizedTest(name = "Erc3525SlotController POST {0}")
    @org.junit.jupiter.params.provider.CsvSource({
            "/slots,ERC3525_SLOT_CREATE",
            "/slots/{slotId}/mint,ERC3525_SLOT_MINT",
            "/tokens/{tokenId}/forced-value-transfer,ERC3525_FORCED_VALUE_TRANSFER_EWG24"})
    @DisplayName("ERC-3525 slot creation, slot mint and forced value transfer need step-up + a second approver (9X-1)")
    void slotValueCreationNeedsSecondApprover(String path, String reason) {
        RequiresStepUp stepUp = post(Erc3525SlotController.class, path).getAnnotation(RequiresStepUp.class);
        assertThat(stepUp).as("@RequiresStepUp on " + path).isNotNull();
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo(reason);
    }
}
