package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.web.HolderController;
import de.makibytes.registerwerk.asset.web.SubscriptionOrderController;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review phase 3, K4 — the method-security contract of T3-08 (subscription) and T3-13 (register-entry
 * edits). Before the fix: investors got 403 on submit (gated by canRead) and the issuer could create
 * entries and clear §17(2) rights with no step-up.
 */
@DisplayName("K4 authorization contract — subscription orders / register entries")
class RegisterEntryAuthorizationContractTest {

    private static Method post(Class<?> c, String path) {
        return Arrays.stream(c.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains(path))
                .findFirst().orElseThrow(() -> new AssertionError(c.getSimpleName() + " has no POST " + path));
    }

    @Test
    @DisplayName("investorCanSubmit: submit is gated by canSubscribe, not the issuer-only canRead")
    void investorCanSubmit() {
        String spel = post(SubscriptionOrderController.class, "/assets/{assetId}/orders")
                .getAnnotation(PreAuthorize.class).value();
        assertThat(spel).contains("canSubscribe").doesNotContain("canRead");
    }

    @Test
    @DisplayName("payment confirmation, settlement and release are issuer/operator actions with step-up")
    void paymentSettleReleaseNeedStepUp() {
        for (String path : new String[] {"/orders/{orderId}/confirm-payment", "/orders/{orderId}/settle",
                "/orders/{orderId}/release"}) {
            Method m = post(SubscriptionOrderController.class, path);
            assertThat(m.getAnnotation(RequiresStepUp.class)).as(path).isNotNull();
            assertThat(m.getAnnotation(PreAuthorize.class).value()).as(path)
                    .contains("hasRole('REGISTRY_ADMIN')").contains("canActAsIssuerForOrder");
        }
    }

    @Test
    @DisplayName("issuerCannotPatchRights: the three register-entry write endpoints are operator-only")
    void registerEntryWritesAreOperatorOnly() {
        for (String name : new String[] {"addHolder", "addSingleEntryHolder"}) {
            Method m = Arrays.stream(HolderController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(PreAuthorize.class).value()).as(name).isEqualTo("hasRole('REGISTRY_ADMIN')");
            assertThat(m.getAnnotation(RequiresStepUp.class)).as(name).isNotNull();
        }
        Method patch = Arrays.stream(HolderController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PatchMapping.class)).findFirst().orElseThrow();
        assertThat(patch.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('REGISTRY_ADMIN')");
        RequiresStepUp stepUp = patch.getAnnotation(RequiresStepUp.class);
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("REGISTER_ENTRY_RIGHTS_CHANGE");
    }

    @Test
    @DisplayName("the issuer can only request a change; executing it is operator + 4-eyes")
    void issuerOnlyRequests() {
        assertThat(post(HolderController.class, "/change-requests").getAnnotation(PreAuthorize.class).value())
                .contains("canActAsIssuer");
        Method execute = post(HolderController.class, "/change-requests/{requestId}/execute");
        assertThat(execute.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('REGISTRY_ADMIN')");
        assertThat(execute.getAnnotation(RequiresStepUp.class).requireSecondApprover()).isTrue();
    }
}
