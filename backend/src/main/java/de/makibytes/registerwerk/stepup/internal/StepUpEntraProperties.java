package de.makibytes.registerwerk.stepup.internal;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conditional Access settings for step-up in Entra mode.
 *
 * <p>The mapping is keyed on {@code @RequiresStepUp#reason()} rather than on a new annotation
 * attribute. There are 77 annotated methods across eleven modules; adding an attribute would mean
 * editing every one of them for something {@code reason()} — already unique per action — gives
 * for free. Note that several reasons are free text with spaces ({@code "Payment rail creation"},
 * {@code "Org suspension"}), so keys must be quoted in YAML.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.auth.step-up.entra")
public class StepUpEntraProperties {

    /**
     * Authentication context id (c1–c99) required by default. Deliberately no default value:
     * silently falling back to {@code c1} would either fail closed everywhere or, worse, accept
     * a context that was never published to the app and thus never actually enforced.
     */
    private String authContextId = "";

    /** Per-action overrides, so e.g. a forced burn can demand a stricter context than a KYC approval. */
    private Map<String, String> reasonOverrides = new HashMap<>();

    /**
     * The {@code authorization_uri} advertised in the claims challenge. Must match the realm:
     * with {@code /common}, {@code realm} has to be the empty string.
     */
    private String authorizationUri = "https://login.microsoftonline.com/common/oauth2/authorize";

    public String getAuthContextId() { return authContextId; }
    public void setAuthContextId(String v) { this.authContextId = v == null ? "" : v.trim(); }

    public Map<String, String> getReasonOverrides() { return reasonOverrides; }
    public void setReasonOverrides(Map<String, String> v) { this.reasonOverrides = v == null ? new HashMap<>() : v; }

    public String getAuthorizationUri() { return authorizationUri; }
    public void setAuthorizationUri(String v) { this.authorizationUri = v; }
}
