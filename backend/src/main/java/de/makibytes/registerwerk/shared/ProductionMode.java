package de.makibytes.registerwerk.shared;

import org.springframework.core.env.Environment;

/**
 * Single resolution of "is this a production deployment" (7A-02). Reads the Spring
 * {@link Environment}, so an OS variable, a {@code -D} system property and any config source are all
 * honoured identically by every readiness check, cookie and SSRF policy that consults it. Previously
 * some classes read {@code System.getenv} and others {@code @Value}, so a {@code -D} flag hardened
 * cookies but left the audit/KEK/HSM gates off.
 */
public class ProductionMode {

    public static final String ENV_NAME = "REGISTERWERK_PRODUCTION_MODE";
    public static final String PROPERTY_NAME = "registerwerk.production-mode";

    private final boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    public static ProductionMode of(Environment environment) {
        return new ProductionMode(resolve(environment));
    }

    public static ProductionMode of(boolean enabled) {
        return new ProductionMode(enabled);
    }

    private ProductionMode(boolean enabled) {
        this.enabled = enabled;
    }

    public static boolean resolve(Environment environment) {
        return "true".equalsIgnoreCase(trim(environment.getProperty(ENV_NAME)))
                || "true".equalsIgnoreCase(trim(environment.getProperty(PROPERTY_NAME)));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
