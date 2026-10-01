package de.makibytes.registerwerk.payment.web;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentRailAdminController: create / enable / attest need step-up + second approver (5A-11)")
class PaymentRailAdminControllerStepUpTest {

    @Test
    void createEnableUpdateAndAttestRequireSecondApprover() {
        Map<String, String> expected = Map.of(
                "createRail", "Payment rail creation",
                "updateRail", "Payment rail update",
                "enableRail", "Payment rail enablement",
                "verifyMicar", "Payment rail MiCAR attestation");
        int seen = 0;
        for (Method m : PaymentRailAdminController.class.getDeclaredMethods()) {
            String reason = expected.get(m.getName());
            if (reason == null) continue;
            seen++;
            RequiresStepUp step = m.getAnnotation(RequiresStepUp.class);
            assertThat(step).as(m.getName()).isNotNull();
            assertThat(step.requireSecondApprover()).as(m.getName()).isTrue();
            assertThat(step.reason()).isEqualTo(reason);
        }
        assertThat(seen).isEqualTo(expected.size());
    }
}
