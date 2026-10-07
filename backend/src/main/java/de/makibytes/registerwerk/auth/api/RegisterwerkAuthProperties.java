package de.makibytes.registerwerk.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import de.makibytes.registerwerk.shared.ProductionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "registerwerk.auth")
public class RegisterwerkAuthProperties implements EnvironmentAware {

    /** Resolved through the one shared {@link ProductionMode}; a bare {@code new} (unit tests) is non-production. */
    private ProductionMode productionMode = ProductionMode.of(false);

    private boolean entraEnabled = false;
    @NotBlank(message = "registerwerk.auth.dev-secret must not be blank")
    private String devSecret = "registerwerk-dev-jwt-secret-change-in-production!!";
    @Positive(message = "registerwerk.auth.token-ttl-seconds must be greater than zero")
    private long tokenTtlSeconds = 28800L;
    /**
     * Expected {@code aud} of access tokens from the OIDC issuer. Blank disables the check —
     * acceptable in local mode, but in an Entra tenant it means a token minted for any other
     * application in the same tenant is accepted here, so {@code ProductionReadinessCheck}
     * requires it once Entra sign-in is on.
     */
    private String audience = "";
    private DefaultAdmin defaultAdmin = new DefaultAdmin();
    /** Impersonation session lifetime (token and session row). */
    @Positive
    private long impersonationTtlSeconds = 1800L;
    /** Validity of the one-time handoff code carried in the handoff URL. */
    @Positive
    private long impersonationHandoffTtlSeconds = 60L;
    /**
     * Session-guard cache TTL: after a revocation on another replica, a token can live at most this
     * long (the documented revocation SLA). In-process changes evict immediately.
     */
    @Positive
    private long sessionGuardCacheSeconds = 15L;
    /**
     * Reject tokens whose {@code sub} has no {@code app_user} row. Always on in production; only a
     * test profile that mints tokens for synthetic users switches it off.
     */
    private boolean rejectUnknownUsers = true;
    /**
     * Endpoints an ACT_ON_BEHALF impersonation may never call, as {@code "METHOD /ant/pattern"}
     * (customer attestations and account/identity administration).
     */
    private java.util.List<String> impersonationDenyPatterns = new java.util.ArrayList<>(java.util.List.of(
            "POST /api/v1/trading/history/*/confirm-payment",
            "POST /api/v1/trading/history/*/dispute-payment",
            "POST /api/v1/trading/history/*/settle",
            "POST /api/v1/repo-desk/trades/**/declare",
            "POST /api/v1/company/users/**",
            "PATCH /api/v1/company/users/**",
            "DELETE /api/v1/company/users/**",
            "PUT /api/v1/company/idp",
            "* /api/v1/me/webhooks/**",
            "* /api/v1/me/org-identity/**",
            "* /api/v1/company/org-identity/**",
            "DELETE /api/v1/entities/*/kyc/documents/**"));

    public static class DefaultAdmin {
        private String email;
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public boolean isEntraEnabled() { return entraEnabled; }
    public void setEntraEnabled(boolean entraEnabled) { this.entraEnabled = entraEnabled; }

    public String getDevSecret() { return devSecret; }
    public void setDevSecret(String devSecret) { this.devSecret = devSecret == null ? "" : devSecret.trim(); }

    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience == null ? "" : audience.trim(); }

    public long getImpersonationTtlSeconds() { return impersonationTtlSeconds; }
    public void setImpersonationTtlSeconds(long v) { this.impersonationTtlSeconds = v; }
    public long getImpersonationHandoffTtlSeconds() { return impersonationHandoffTtlSeconds; }
    public void setImpersonationHandoffTtlSeconds(long v) { this.impersonationHandoffTtlSeconds = v; }
    public long getSessionGuardCacheSeconds() { return sessionGuardCacheSeconds; }
    public void setSessionGuardCacheSeconds(long v) { this.sessionGuardCacheSeconds = v; }
    public boolean isRejectUnknownUsers() { return rejectUnknownUsers; }
    public void setRejectUnknownUsers(boolean v) { this.rejectUnknownUsers = v; }
    public java.util.List<String> getImpersonationDenyPatterns() { return impersonationDenyPatterns; }
    public void setImpersonationDenyPatterns(java.util.List<String> v) { this.impersonationDenyPatterns = v; }

    /**
     * Whether an IdP token may be linked to an existing account by e-mail when the token does not
     * assert a verified address ({@code xms_edov} / {@code email_verified}). Blank/null = allowed
     * outside production mode, refused in production mode ({@code REGISTERWERK_PRODUCTION_MODE=true}).
     * A token that explicitly asserts the address is NOT verified is always refused.
     */
    private Boolean linkByEmailWithoutVerification;

    public Boolean getLinkByEmailWithoutVerification() { return linkByEmailWithoutVerification; }
    public void setLinkByEmailWithoutVerification(Boolean v) { this.linkByEmailWithoutVerification = v; }

    /** Effective value of {@link #getLinkByEmailWithoutVerification()} (see its field documentation). */
    public boolean linkByEmailWithoutVerificationAllowed() {
        if (linkByEmailWithoutVerification != null) {
            return linkByEmailWithoutVerification;
        }
        return !productionMode.enabled();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.productionMode = ProductionMode.of(environment);
    }

    public DefaultAdmin getDefaultAdmin() { return defaultAdmin; }
    public void setDefaultAdmin(DefaultAdmin defaultAdmin) { this.defaultAdmin = defaultAdmin; }
}
