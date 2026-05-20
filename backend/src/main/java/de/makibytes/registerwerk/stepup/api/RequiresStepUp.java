package de.makibytes.registerwerk.stepup.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method or class as requiring step-up authentication:
 * a fresh elevated-privilege token (acr=stepup, max age controlled by maxAgeMinutes)
 * AND, when requireSecondApprover=true, a second REGISTRY_ADMIN bearer token
 * in the X-Dual-Control-Token header.
 *
 * Used on regulator-grade actions (forced-transfer, force-burn, KYC override,
 * Sperrvermerk create/lift, key import) per the 4-eyes principle (GwG §25k,
 * BaFin MaRisk AT 4.3.1 Funktionstrennung).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresStepUp {
    /** Maximum age in minutes of the step-up token's issue time. Default: 10. */
    int maxAgeMinutes() default 10;

    /** Whether a second approver token (X-Dual-Control-Token) is required. */
    boolean requireSecondApprover() default false;

    /** Human-readable reason logged in the audit trail. */
    String reason() default "REGULATOR_GRADE_ACTION";
}
