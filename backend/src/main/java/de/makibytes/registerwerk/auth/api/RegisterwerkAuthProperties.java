package de.makibytes.registerwerk.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "registerwerk.auth")
public class RegisterwerkAuthProperties {

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

    public DefaultAdmin getDefaultAdmin() { return defaultAdmin; }
    public void setDefaultAdmin(DefaultAdmin defaultAdmin) { this.defaultAdmin = defaultAdmin; }
}
