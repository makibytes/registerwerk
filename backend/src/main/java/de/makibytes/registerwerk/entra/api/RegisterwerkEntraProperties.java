package de.makibytes.registerwerk.entra.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Microsoft Entra ID integration.
 *
 * <p>Note the two independent switches. {@code registerwerk.auth.entra-enabled} decides how users
 * <em>sign in</em>; {@link #isSupportEnabled()} decides whether Registerwerk may call Microsoft
 * Graph on their behalf. A deployment can perfectly well run Entra sign-in while leaving Graph
 * off — it just loses the 2FA status page and the operator support console.
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.entra")
public class RegisterwerkEntraProperties {

    /** Directory (tenant) id of the operator's workforce tenant. */
    private String tenantId = "";

    /** App registration of the backend API — the expected {@code aud} of incoming access tokens. */
    private String clientId = "";

    /** App registration of the customer SPA, handed to the browser by /public/auth/config. */
    private String spaClientId = "";

    /** Client secret of the API registration, used only for app-only Graph calls. */
    private String clientSecret = "";

    /** Scope the SPA requests for the backend API, e.g. {@code api://<api-client-id>/access_as_user}. */
    private String apiScope = "";

    private String graphBaseUrl = "https://graph.microsoft.com/v1.0";

    private String authorityBaseUrl = "https://login.microsoftonline.com";

    /** Master switch for every Graph call. Off by default so nothing reaches out unless asked. */
    private boolean supportEnabled = false;

    /** Where the customer is sent to register a second factor — Microsoft owns this page. */
    private String mfaSetupUrl = "https://mysignins.microsoft.com/security-info";

    /**
     * Opt-in hard gate forcing customers to /security until a second factor is registered.
     * Off by default: Conditional Access already blocks unenrolled users at sign-in, so this is
     * redundant, and reading status from Graph on every navigation would make a Graph outage a
     * total portal outage. The guard fails open on error even when enabled.
     */
    private boolean requireTwoFactorEnrolment = false;

    /** Minimum seconds between forced Graph re-reads per user, so status polling cannot flood Graph. */
    private int statusRefreshThrottleSeconds = 10;

    private Tap tap = new Tap();

    /** Defaults for operator-issued Temporary Access Passes. Must fit the tenant's TAP policy. */
    public static class Tap {
        private int defaultLifetimeMinutes = 60;
        private boolean defaultUsableOnce = true;

        public int getDefaultLifetimeMinutes() { return defaultLifetimeMinutes; }
        public void setDefaultLifetimeMinutes(int v) { this.defaultLifetimeMinutes = v; }

        public boolean isDefaultUsableOnce() { return defaultUsableOnce; }
        public void setDefaultUsableOnce(boolean v) { this.defaultUsableOnce = v; }
    }

    /** True when enough is configured to actually talk to Graph. */
    public boolean isGraphConfigured() {
        return supportEnabled
                && !tenantId.isBlank()
                && !clientId.isBlank()
                && !clientSecret.isBlank();
    }

    /** OIDC authority for this tenant, e.g. {@code https://login.microsoftonline.com/<tenant-id>}. */
    public String getAuthority() {
        return authorityBaseUrl + "/" + tenantId;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId == null ? "" : tenantId.trim(); }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId == null ? "" : clientId.trim(); }

    public String getSpaClientId() { return spaClientId; }
    public void setSpaClientId(String spaClientId) { this.spaClientId = spaClientId == null ? "" : spaClientId.trim(); }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret == null ? "" : clientSecret.trim(); }

    public String getApiScope() { return apiScope; }
    public void setApiScope(String apiScope) { this.apiScope = apiScope == null ? "" : apiScope.trim(); }

    public String getGraphBaseUrl() { return graphBaseUrl; }
    public void setGraphBaseUrl(String graphBaseUrl) { this.graphBaseUrl = graphBaseUrl; }

    public String getAuthorityBaseUrl() { return authorityBaseUrl; }
    public void setAuthorityBaseUrl(String authorityBaseUrl) { this.authorityBaseUrl = authorityBaseUrl; }

    public boolean isSupportEnabled() { return supportEnabled; }
    public void setSupportEnabled(boolean supportEnabled) { this.supportEnabled = supportEnabled; }

    public String getMfaSetupUrl() { return mfaSetupUrl; }
    public void setMfaSetupUrl(String mfaSetupUrl) { this.mfaSetupUrl = mfaSetupUrl; }

    public boolean isRequireTwoFactorEnrolment() { return requireTwoFactorEnrolment; }
    public void setRequireTwoFactorEnrolment(boolean v) { this.requireTwoFactorEnrolment = v; }

    public int getStatusRefreshThrottleSeconds() { return statusRefreshThrottleSeconds; }
    public void setStatusRefreshThrottleSeconds(int v) { this.statusRefreshThrottleSeconds = v; }

    public Tap getTap() { return tap; }
    public void setTap(Tap tap) { this.tap = tap; }
}
