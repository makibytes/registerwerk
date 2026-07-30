package de.makibytes.registerwerk.entra.api;

import java.time.Instant;
import java.util.List;

/**
 * Second-factor registration status for one user.
 *
 * @param applicable    false when Entra is not in play at all (local auth mode, or a LOCAL
 *                      account) — the UI then shows "not applicable" rather than an error
 * @param identityModel where the identity is hosted
 * @param registered    true when at least one {@link EntraAuthMethodType#isSecondFactor()}
 *                      method is registered
 * @param methods       the registered methods; empty when unknown or not managed here
 * @param checkedAt     when this was read from Graph; null when never checked
 * @param message       optional explanation for the UI when {@code applicable} is false or
 *                      the status could not be determined
 */
public record EntraUserMfaStatus(
        boolean applicable,
        EntraIdentityModel identityModel,
        boolean registered,
        List<EntraAuthMethod> methods,
        Instant checkedAt,
        String message) {

    public EntraUserMfaStatus {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    /**
     * Entra plays no part for this user — local auth mode, or a LOCAL account. Deliberately a
     * successful "nothing to do here" rather than an error: a status read that cannot run is
     * "unknown", and turning it into a failure would break the /security page in dev.
     */
    public static EntraUserMfaStatus notApplicable(String message) {
        return new EntraUserMfaStatus(false, EntraIdentityModel.LOCAL, false, List.of(), null, message);
    }

    /** Identity is federated to the customer's own tenant — we can see nothing and manage nothing. */
    public static EntraUserMfaStatus federated(String message) {
        return new EntraUserMfaStatus(true, EntraIdentityModel.FEDERATED, false, List.of(), null, message);
    }

    /** True when the methods list is authoritative, i.e. it actually came from Graph. */
    public boolean managedHere() {
        return applicable && identityModel.isManagedHere();
    }
}
