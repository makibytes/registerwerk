package de.makibytes.registerwerk.admin.internal;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OperatorUserService role rules (T6-05 SUPPORT_AGENT)")
class OperatorUserServiceRolesTest {

    @Test
    @DisplayName("granting or removing SUPPORT_AGENT is a gated role change (step-up + second approver)")
    void supportAgentIsGated() {
        assertThat(OperatorUserService.touchesGatedRole(Set.of(AppUserRole.AUDIT), Set.of(AppUserRole.AUDIT, AppUserRole.SUPPORT_AGENT)))
                .isTrue();
        assertThat(OperatorUserService.touchesGatedRole(Set.of(AppUserRole.SUPPORT_AGENT), Set.of(AppUserRole.AUDIT)))
                .isTrue();
    }

    @Test
    @DisplayName("SUPPORT_AGENT is an operator-only role: refused for company-scoped users")
    void supportAgentNotForCompanyUsers() {
        assertThatThrownBy(() -> OperatorUserService.validateRolesForContext(java.util.UUID.randomUUID(),
                Set.of(AppUserRole.SUPPORT_AGENT)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
