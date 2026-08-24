package de.makibytes.registerwerk.entra.web;

import de.makibytes.registerwerk.entra.internal.TwoFactorStatusService;
import de.makibytes.registerwerk.entra.web.dto.TwoFactorStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer self-service: "is my second factor set up?".
 *
 * <p>Read-only, and deliberately <strong>not</strong> step-up protected — requiring a second
 * factor to check whether you have a second factor would lock out exactly the users who need
 * this page.
 *
 * <p>There is no enrolment endpoint here because there cannot be one: Microsoft Graph exposes no
 * way to create a Microsoft Authenticator or software-OATH method ({@code softwareOathMethods}
 * and {@code microsoftAuthenticatorMethods} support only list, get and delete, and
 * {@code secretKey} always returns null). Entra owns the TOTP secret, so registration happens on
 * Microsoft's combined security-info page and the app links users there.
 */
@RestController
@RequestMapping("/api/v1/security")
@PreAuthorize("isAuthenticated()")
public class SecurityController {

    private final TwoFactorStatusService statusService;

    SecurityController(TwoFactorStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/two-factor")
    public ResponseEntity<TwoFactorStatusResponse> status(Authentication authentication) {
        return ResponseEntity.ok(statusService.statusFor(authentication, false));
    }

    /**
     * Re-reads status from Microsoft Graph. Called by the /security page while the user is away
     * registering, so it is throttled per user — a few tabs left open would otherwise become a
     * steady stream of Graph calls and eventually tenant-wide throttling.
     */
    @PostMapping("/two-factor/refresh")
    public ResponseEntity<TwoFactorStatusResponse> refresh(Authentication authentication) {
        return ResponseEntity.ok(statusService.statusFor(authentication, true));
    }
}
