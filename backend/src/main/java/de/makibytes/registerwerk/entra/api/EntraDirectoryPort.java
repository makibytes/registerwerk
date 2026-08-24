package de.makibytes.registerwerk.entra.api;

import java.util.List;

/**
 * The only way into Microsoft Graph. Fail-closed, in the same spirit as
 * {@code screening.ScreeningGate} and {@code orgidentity.PermissionGate}, but with a
 * deliberate asymmetry between reads and writes:
 *
 * <ul>
 *   <li><strong>Reads</strong> degrade to "not applicable / unknown" when the integration is
 *       disabled. A status read that cannot run is not an error — making it one would turn a
 *       Graph outage into a portal outage, and would break the /security page in local mode.</li>
 *   <li><strong>Mutations</strong> throw {@link EntraNotConfiguredException}. A write that
 *       cannot run must fail loudly; silently doing nothing while an operator believes they
 *       have reset someone's MFA is the worst possible outcome.</li>
 * </ul>
 *
 * <p>All ids are Entra object ids (the token's {@code oid}), never {@code app_user.id}.
 */
public interface EntraDirectoryPort {

    /** True when a real Graph adapter is wired up and configured. */
    boolean isEnabled();

    /** Second-factor registration status. Never throws for configuration reasons — see class doc. */
    EntraUserMfaStatus getMfaStatus(String entraObjectId);

    /** Every registered method, newest-irrelevant order as Graph returns them. */
    List<EntraAuthMethod> listAuthMethods(String entraObjectId);

    /**
     * Removes one method.
     *
     * @throws IllegalArgumentException             if the type is not individually deletable
     * @throws EntraNotConfiguredException          if the integration is disabled
     * @throws EntraDirectoryException              on a Graph failure
     */
    void deleteAuthMethod(String entraObjectId, EntraAuthMethodType type, String methodId);

    /**
     * Removes every deletable method so the user is forced to re-register at next sign-in —
     * Graph has no single "reset MFA" call, so this is the documented substitute.
     *
     * <p>Deletes the user's default method <em>last</em> and collects per-method failures
     * instead of aborting: Entra is reported to refuse deleting the default method while others
     * remain, but that behaviour is unverified field lore rather than documented, so it is
     * handled as an expected outcome rather than asserted.
     *
     * <p>Callers should follow this with {@link #revokeSignInSessions(String)} — deleting
     * methods does not invalidate existing refresh tokens or browser sessions.
     */
    ResetOutcome resetAllAuthMethods(String entraObjectId);

    /**
     * Invalidates the user's refresh tokens and browser session cookies. Must be called
     * explicitly; neither a password reset nor method deletion does this on its own.
     */
    void revokeSignInSessions(String entraObjectId);

    /**
     * Issues a Temporary Access Pass. Entra permits only one per user — issuing a new one
     * silently replaces any existing one.
     *
     * @param lifetimeMinutes must lie within the tenant's TAP policy (10–43200)
     * @param usableOnce      single-use passes require the user to finish registering within
     *                        10 minutes of signing in
     * @throws EntraUnsupportedForIdentityModelException for an external guest, who cannot hold a TAP
     */
    TemporaryAccessPass issueTemporaryAccessPass(String entraObjectId, int lifetimeMinutes, boolean usableOnce);

    /** Conditional Access authentication contexts defined in the tenant, for boot-time validation. */
    List<AuthContextRef> listAuthenticationContexts();

    /**
     * Whether this principal is a member or a guest of the operator's tenant. Falls back to
     * {@link EntraIdentityModel#WORKFORCE_MEMBER} when it cannot be determined — both are
     * manageable, and the one capability guests lack (a Temporary Access Pass) is separately
     * gated by {@link #isExternalGuest}.
     */
    EntraIdentityModel classifyPrincipal(String entraObjectId);

    /**
     * Whether this principal is an <em>external</em> B2B guest, whose credentials live in another
     * tenant. Entra will not issue a Temporary Access Pass to such an account.
     */
    boolean isExternalGuest(String entraObjectId);

    /**
     * @param deleted  methods successfully removed
     * @param failures human-readable reasons, one per method that could not be removed
     */
    record ResetOutcome(List<EntraAuthMethod> deleted, List<String> failures) {

        public ResetOutcome {
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public boolean complete() {
            return failures.isEmpty();
        }
    }
}
