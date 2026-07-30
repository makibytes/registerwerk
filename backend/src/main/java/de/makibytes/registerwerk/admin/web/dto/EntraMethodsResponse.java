package de.makibytes.registerwerk.admin.web.dto;

import java.util.List;

/**
 * What the operator console needs to render a user's 2FA state and decide which actions are
 * even offerable.
 *
 * @param identityModel  LOCAL / WORKFORCE_MEMBER / WORKFORCE_GUEST / FEDERATED
 * @param managedHere    false for a federated identity — every mutating action is refused
 * @param tapSupported   false for an external B2B guest, whom Entra will not issue a Temporary
 *                       Access Pass; the console disables that button and says why
 * @param message        explanation shown when methods cannot be listed
 */
public record EntraMethodsResponse(
        String identityModel,
        boolean managedHere,
        boolean tapSupported,
        boolean registered,
        List<EntraAuthMethodDto> methods,
        String message) {
}
