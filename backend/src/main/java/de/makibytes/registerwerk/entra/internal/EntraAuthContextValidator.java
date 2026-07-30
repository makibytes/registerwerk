package de.makibytes.registerwerk.entra.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.makibytes.registerwerk.entra.api.AuthContextRef;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Checks at startup that every configured Conditional Access authentication context actually
 * exists in the tenant and is published to applications.
 *
 * <p>Microsoft's guidance is not to hard-code authentication context ids, so Registerwerk keeps
 * them in configuration — this is the other half of that bargain: configuration that is verified
 * against the live tenant instead of merely trusted.
 *
 * <p>An <strong>unpublished</strong> context is the failure worth catching. It is invisible to
 * resources, so it can never be satisfied: the SPA redirects, Entra returns a token without the
 * claim, the aspect challenges again, and the user spins in a redirect loop with nothing in the
 * logs to explain it.
 *
 * <p>Reads the ids from configuration rather than from {@code stepup}: the {@code entra} module
 * must not depend on {@code stepup}, since the operator support endpoints in {@code admin} need
 * both.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.entra.support-enabled", havingValue = "true")
class EntraAuthContextValidator {

    private static final Logger log = LoggerFactory.getLogger(EntraAuthContextValidator.class);

    private final EntraDirectoryPort directory;
    private final EntraStepUpContextProperties stepUpProperties;
    private final boolean productionMode;

    EntraAuthContextValidator(
            EntraDirectoryPort directory,
            EntraStepUpContextProperties stepUpProperties,
            @Value("${REGISTERWERK_PRODUCTION_MODE:false}") boolean productionMode) {
        this.directory = directory;
        this.stepUpProperties = stepUpProperties;
        this.productionMode = productionMode;
    }

    @PostConstruct
    void validate() {
        Set<String> configured = new LinkedHashSet<>();
        if (!stepUpProperties.getAuthContextId().isBlank()) {
            configured.add(stepUpProperties.getAuthContextId());
        }
        stepUpProperties.getReasonOverrides().values().stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .forEach(configured::add);

        if (configured.isEmpty()) {
            log.warn("Microsoft Graph is enabled but no Conditional Access authentication context is "
                    + "configured. Step-up-protected endpoints will fail closed. "
                    + "Set ENTRA_STEPUP_AUTH_CONTEXT_ID.");
            return;
        }

        Map<String, AuthContextRef> available;
        try {
            List<AuthContextRef> contexts = directory.listAuthenticationContexts();
            available = contexts.stream()
                    .collect(Collectors.toMap(AuthContextRef::id, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            // Don't refuse to boot over a transient Graph problem — the health indicator reports
            // it, and failing startup would turn a Graph blip into an outage.
            log.error("Could not verify Conditional Access authentication contexts against the tenant: {}",
                    e.getMessage());
            return;
        }

        for (String id : configured) {
            AuthContextRef context = available.get(id);
            if (context == null) {
                fail("Conditional Access authentication context '" + id + "' is configured for step-up "
                        + "but does not exist in tenant. Create it under Entra ID > Conditional Access "
                        + "> Authentication context.");
            } else if (!context.isAvailable()) {
                fail("Conditional Access authentication context '" + id + "' (" + context.displayName()
                        + ") exists but is not published to applications. An unpublished context can "
                        + "never be satisfied and will produce a sign-in redirect loop. Tick "
                        + "'Publish to apps'.");
            } else {
                log.info("Verified step-up authentication context: id={} displayName=\"{}\"",
                        id, context.displayName());
            }
        }
    }

    private void fail(String message) {
        if (productionMode) {
            throw new IllegalStateException("SECURITY: " + message);
        }
        log.error(message);
    }
}
