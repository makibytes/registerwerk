package de.makibytes.registerwerk.entra.web;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthConfigController: production flag for the portals (T6-05 / T2-20)")
class AuthConfigControllerTest {

    private boolean productionFlag(boolean production) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        return new AuthConfigController(new RegisterwerkAuthProperties(), new RegisterwerkEntraProperties(), env)
                .config().getBody().productionMode();
    }

    @Test
    @DisplayName("productionMode mirrors ProductionMode")
    void productionModeFlag() {
        assertThat(productionFlag(true)).isTrue();
        assertThat(productionFlag(false)).isFalse();
    }
}
