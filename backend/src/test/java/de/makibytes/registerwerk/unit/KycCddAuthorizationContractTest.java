package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.kyc.web.BeneficialOwnerController;
import de.makibytes.registerwerk.kyc.web.KycController;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review phase 6, K6 - method-security contract of the KYC document / UBO endpoints. Before the fix the
 * document list/download and the UBO list were open to any user of the entity (a TRADER downloaded
 * passports), and ceasing a beneficial owner needed neither step-up nor a second approver.
 */
@DisplayName("K6 authorization contract - KYC documents, UBO list, cease, EDD")
class KycCddAuthorizationContractTest {

    private static Method method(Class<?> c, String name) {
        return Arrays.stream(c.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(c.getSimpleName() + "." + name + " missing"));
    }

    private static void assertCompanyAdminOnly(Method m) {
        String spel = m.getAnnotation(PreAuthorize.class).value();
        assertThat(spel).contains("hasAnyRole(");
        assertThat(spel).contains("hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner");
        // the ownership check is only reachable together with COMPANY_ADMIN, never on its own
        assertThat(spel.replace("(hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner", ""))
                .doesNotContain("@entityOwnershipChecker.isOwner");
    }

    @Test
    @DisplayName("document list and download: operator compliance roles or the entity's COMPANY_ADMIN, not any entity user")
    void documentReadRestricted() {
        assertCompanyAdminOnly(method(KycController.class, "listDocuments"));
        assertCompanyAdminOnly(method(KycController.class, "downloadDocument"));
        assertThat(method(KycController.class, "listDocuments").getAnnotation(PreAuthorize.class).value())
                .contains("COMPLIANCE_OFFICER");
    }

    @Test
    @DisplayName("UBO list: same restriction")
    void uboListRestricted() {
        assertCompanyAdminOnly(method(BeneficialOwnerController.class, "list"));
    }

    @Test
    @DisplayName("cease needs step-up and a second approver; EDD approval is REGISTRY_ADMIN with a second approver")
    void ceaseAndEddAreFourEyes() {
        RequiresStepUp cease = method(BeneficialOwnerController.class, "cease").getAnnotation(RequiresStepUp.class);
        assertThat(cease).isNotNull();
        assertThat(cease.requireSecondApprover()).isTrue();
        assertThat(cease.reason()).isEqualTo("BENEFICIAL_OWNER_CEASE");

        Method edd = method(BeneficialOwnerController.class, "approveEdd");
        assertThat(edd.getAnnotation(RequiresStepUp.class).requireSecondApprover()).isTrue();
        assertThat(edd.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('REGISTRY_ADMIN')");
    }
}
