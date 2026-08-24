package de.makibytes.registerwerk.entra.web.dto;

import java.util.List;

/**
 * Everything a single-page app needs to decide how to sign users in, fetched before Angular
 * bootstraps.
 *
 * <p><strong>Why this is a runtime endpoint rather than build-time environment config.</strong>
 * MSAL's {@code PublicClientApplication} needs {@code clientId} and {@code authority} at
 * construction time, so baking them into {@code environment.prod.ts} would mean building a
 * separate frontend image per operator tenant. Fetching them makes one image deployable
 * anywhere.
 *
 * <p>Contains no secrets: a public-client id and an authority URL are both published to the
 * browser during any OIDC redirect anyway. Served unauthenticated under
 * {@code /api/v1/public/**} and cached by Kong for 30 s.
 *
 * @param mode                     {@code "LOCAL"} (built-in password login) or {@code "ENTRA"}
 * @param authority                OIDC authority, e.g. {@code https://login.microsoftonline.com/<tenant>}
 * @param clientId                 the SPA's app registration id
 * @param scopes                   scopes to request for the backend API
 * @param localRegistrationEnabled whether invite/password-reset links apply — false in Entra mode,
 *                                 where those endpoints reject the operation anyway
 * @param twoFactorPageEnabled     whether /security can show real status (needs Graph)
 * @param requireTwoFactorEnrolment whether unenrolled users are redirected to /security
 * @param mfaSetupUrl              Microsoft's combined security-info registration page — the app
 *                                 cannot register an authenticator itself, so it links here
 */
public record AuthConfigResponse(
        String mode,
        String authority,
        String clientId,
        List<String> scopes,
        boolean localRegistrationEnabled,
        boolean twoFactorPageEnabled,
        boolean requireTwoFactorEnrolment,
        String mfaSetupUrl) {
}
