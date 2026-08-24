/**
 * Microsoft Entra ID integration: authentication-method status for the customer
 * self-service 2FA page, and the directory operations the operator support console
 * needs for lost-phone recovery (method reset, session revocation, Temporary Access Pass).
 *
 * <p>Deliberately a module of its own rather than part of {@code auth}: {@code customer}
 * already depends on {@code auth}, and this code needs {@code customer.api.LegalEntity}
 * for per-entity federation config — folding it into {@code auth} would create the cycle
 * {@code auth → customer → auth} that {@code ModulithArchitectureTest} rejects.
 *
 * <p>It must also never depend on {@code stepup}: the operator support endpoints carry
 * {@code @RequiresStepUp} and therefore live in {@code admin}, not here. The Conditional
 * Access authentication-context id stays pure configuration; this module only validates
 * it against the tenant at boot.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Microsoft Entra ID")
package de.makibytes.registerwerk.entra;
