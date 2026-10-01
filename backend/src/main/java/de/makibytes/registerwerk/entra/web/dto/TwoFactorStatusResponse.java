package de.makibytes.registerwerk.entra.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Second-factor status for the signed-in customer, as rendered by the /security page.
 *
 * @param applicable     false in local auth mode — the page then explains that 2FA is managed by
 *                       Microsoft Entra ID and not active in this environment, rather than
 *                       showing a broken widget
 * @param identityModel  LOCAL / WORKFORCE_MEMBER / WORKFORCE_GUEST / FEDERATED
 * @param managedHere    false for a federated identity, whose own organisation manages its MFA
 * @param registered     whether at least one second factor is registered
 * @param methods        human-readable method labels, e.g. "Microsoft Authenticator (Pixel 9)"
 * @param checkedAt      when this was read from Microsoft Graph; null when never checked
 * @param setupUrl       Microsoft's combined security-info page — registration happens there,
 *                       because Graph exposes no way to create an authenticator or TOTP method
 * @param message        explanation shown when status is unavailable or does not apply
 * @param stale          true when Graph could not be read and the last recorded state is shown instead
 */
public record TwoFactorStatusResponse(
        boolean applicable,
        String identityModel,
        boolean managedHere,
        boolean registered,
        List<String> methods,
        Instant checkedAt,
        String setupUrl,
        String message,
        boolean stale) {

    public TwoFactorStatusResponse(boolean applicable, String identityModel, boolean managedHere, boolean registered,
                                   List<String> methods, Instant checkedAt, String setupUrl, String message) {
        this(applicable, identityModel, managedHere, registered, methods, checkedAt, setupUrl, message, false);
    }
}
