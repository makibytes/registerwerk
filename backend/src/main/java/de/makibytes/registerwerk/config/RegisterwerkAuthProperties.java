package de.makibytes.registerwerk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.auth")
public class RegisterwerkAuthProperties {

    private boolean entraEnabled = false;
    private String devSecret = "registerwerk-dev-jwt-secret-change-in-production!!";
    private long tokenTtlSeconds = 28800L;
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
    public void setDevSecret(String devSecret) { this.devSecret = devSecret; }

    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }

    public DefaultAdmin getDefaultAdmin() { return defaultAdmin; }
    public void setDefaultAdmin(DefaultAdmin defaultAdmin) { this.defaultAdmin = defaultAdmin; }
}
