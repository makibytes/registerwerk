package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DualControlProperties - what an approval is bound to (C2)")
class DualControlPropertiesTest {

    /**
     * The explicit, documented opt-out: payloads that are not JSON (multipart upload, CSV - an approver cannot
     * reproduce a canonical form) and payloads that are secret key material or a keystore password (they must
     * never be copied into an approval request). Method, path and query stay bound for these.
     */
    private static final Set<String> NON_JSON_REASONS = Set.of("TERM_SHEET_AMENDMENT", "WALLET_IMPORT_KEYSTORE",
            "CASP_REGISTER_IMPORT", "WALLET_IMPORT_RAW", "WALLET_KEYSTORE_EXPORT");

    @Test
    @DisplayName("by default the canonical body is bound for every reason, not only for an allow-list")
    void bodyIsBoundByDefault() {
        DualControlProperties props = new DualControlProperties();
        assertThat(props.bindsBody("KYC_APPROVE")).isTrue();
        assertThat(props.bindsBody("WHITELIST_CHANGE")).isTrue();
        assertThat(props.bindsBody("SOME_FUTURE_REASON")).isTrue();
    }

    @Test
    @DisplayName("an explicit opt-out unbinds exactly the listed reasons")
    void optOutIsExplicit() {
        DualControlProperties props = new DualControlProperties();
        props.setBodyOptOutReasons(List.of("UPLOAD"));
        assertThat(props.bindsBody("UPLOAD")).isFalse();
        assertThat(props.bindsBody("KYC_APPROVE")).isTrue();
        assertThat(props.bindsTarget("UPLOAD")).as("method/path/query stay bound").isTrue();
    }

    @Test
    @DisplayName("rolling back target binding for a reason also unbinds its body")
    void targetRollbackUnbindsBody() {
        DualControlProperties props = new DualControlProperties();
        props.setBindTargetReasons(List.of("OTHER"));
        assertThat(props.bindsBody("KYC_APPROVE")).isFalse();
        assertThat(props.bindsBody("OTHER")).isTrue();
    }

    @Test
    @DisplayName("the shipped application.yml binds the body of every dual-control reason except the documented non-JSON ones")
    void shippedConfigurationBindsEveryDualControlReason() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties source = yaml.getObject();
        DualControlProperties props = new Binder(new MapConfigurationPropertySource(source))
                .bind("registerwerk.auth.step-up.dual-control", DualControlProperties.class)
                .orElseGet(DualControlProperties::new);

        Set<String> reasons = dualControlReasons();
        assertThat(reasons).as("the scan must actually find the dual-control endpoints").hasSizeGreaterThan(50);
        Set<String> unbound = new TreeSet<>();
        for (String reason : reasons) {
            if (!props.bindsBody(reason)) {
                unbound.add(reason);
            }
        }
        assertThat(unbound).as("reasons whose request body an initiator could change after approval")
                .isSubsetOf(NON_JSON_REASONS);
        assertThat(props.getBodyOptOutReasons()).as("the opt-out list is exactly the documented non-JSON reasons")
                .containsExactlyInAnyOrderElementsOf(NON_JSON_REASONS);
    }

    /** Every {@code @RequiresStepUp(requireSecondApprover = true)} reason on a controller in this code base. */
    private static Set<String> dualControlReasons() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> reasons = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents("de.makibytes.registerwerk")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequiresStepUp onClass = controller.getAnnotation(RequiresStepUp.class);
            if (onClass != null && onClass.requireSecondApprover()) {
                reasons.add(onClass.reason());
            }
            for (Method m : controller.getDeclaredMethods()) {
                RequiresStepUp a = m.getAnnotation(RequiresStepUp.class);
                if (a != null && a.requireSecondApprover()) {
                    reasons.add(a.reason());
                }
            }
        }
        return reasons;
    }
}
