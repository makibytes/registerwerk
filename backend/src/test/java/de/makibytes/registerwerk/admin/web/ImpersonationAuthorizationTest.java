package de.makibytes.registerwerk.admin.web;

import de.makibytes.registerwerk.customer.web.CustomerController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("T6-05: who may start impersonation sessions (method security expressions)")
class ImpersonationAuthorizationTest {

    private static String expression(Class<?> type, String method) {
        Method m = Arrays.stream(type.getDeclaredMethods()).filter(x -> x.getName().equals(method)).findFirst().orElseThrow();
        PreAuthorize onMethod = m.getAnnotation(PreAuthorize.class);
        PreAuthorize onType = type.getAnnotation(PreAuthorize.class);
        return onMethod != null ? onMethod.value() : onType.value();
    }

    @Test
    @DisplayName("READ_ONLY impersonation: REGISTRY_ADMIN or SUPPORT_AGENT")
    void readOnlyAllowsSupportAgent() {
        String expr = expression(AdminImpersonationController.class, "impersonate");
        assertThat(expr).contains("REGISTRY_ADMIN").contains("SUPPORT_AGENT");
    }

    @Test
    @DisplayName("ACT_ON_BEHALF: REGISTRY_ADMIN only, never SUPPORT_AGENT")
    void actOnBehalfAdminOnly() {
        String expr = expression(AdminImpersonationController.class, "actOnBehalf");
        assertThat(expr).contains("REGISTRY_ADMIN").doesNotContain("SUPPORT_AGENT");
    }

    @Test
    @DisplayName("SUPPORT_AGENT can list companies to pick one, and nothing else on the entity API changes")
    void supportAgentCanListEntities() {
        assertThat(expression(CustomerController.class, "listEntities")).contains("SUPPORT_AGENT");
        assertThat(expression(CustomerController.class, "updateEntity")).doesNotContain("SUPPORT_AGENT");
    }
}
