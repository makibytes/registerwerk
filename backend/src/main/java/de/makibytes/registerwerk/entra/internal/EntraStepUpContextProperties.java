package de.makibytes.registerwerk.entra.internal;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A read-only view of the step-up authentication-context configuration, so
 * {@link EntraAuthContextValidator} can check those ids against the tenant.
 *
 * <p>It binds the same prefix as {@code stepup}'s own properties class rather than injecting it,
 * because {@code entra} must not depend on {@code stepup} — the operator support endpoints in
 * {@code admin} need both modules, and a dependency in this direction would make that a cycle.
 * Binding the same keys twice is cheap and keeps the boundary intact.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.auth.step-up.entra")
class EntraStepUpContextProperties {

    private String authContextId = "";
    private Map<String, String> reasonOverrides = new HashMap<>();

    String getAuthContextId() { return authContextId; }
    void setAuthContextId(String v) { this.authContextId = v == null ? "" : v.trim(); }

    Map<String, String> getReasonOverrides() { return reasonOverrides; }
    void setReasonOverrides(Map<String, String> v) { this.reasonOverrides = v == null ? new HashMap<>() : v; }
}
