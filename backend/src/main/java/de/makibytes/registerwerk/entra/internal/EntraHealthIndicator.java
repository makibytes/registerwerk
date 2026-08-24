package de.makibytes.registerwerk.entra.internal;

import java.util.List;

import de.makibytes.registerwerk.entra.api.AuthContextRef;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports Graph reachability at {@code /actuator/health/entra}.
 *
 * <p>A tenant misconfiguration — an expired client secret, a missing directory role, an
 * authentication context that was never published to the app — otherwise surfaces as a redirect
 * loop in the customer's browser or a mystery 500 in the operator console, hours after the
 * change that caused it. Making it a health check turns that into something monitoring sees.
 */
@Component("entra")
@ConditionalOnProperty(name = "registerwerk.entra.support-enabled", havingValue = "true")
class EntraHealthIndicator implements HealthIndicator {

    private final EntraDirectoryPort directory;
    private final RegisterwerkEntraProperties props;

    EntraHealthIndicator(EntraDirectoryPort directory, RegisterwerkEntraProperties props) {
        this.directory = directory;
        this.props = props;
    }

    @Override
    public Health health() {
        if (!props.isGraphConfigured()) {
            return Health.down()
                    .withDetail("reason", "registerwerk.entra.support-enabled is true but "
                            + "tenant-id, client-id or client-secret is blank")
                    .build();
        }

        try {
            // Doubles as a token-acquisition check: the credential is exercised by the call.
            List<AuthContextRef> contexts = directory.listAuthenticationContexts();
            return Health.up()
                    .withDetail("tenantId", props.getTenantId())
                    .withDetail("graphBaseUrl", props.getGraphBaseUrl())
                    .withDetail("authenticationContexts", contexts.size())
                    .withDetail("publishedAuthenticationContexts",
                            contexts.stream().filter(AuthContextRef::isAvailable).count())
                    .build();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("tenantId", props.getTenantId())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
