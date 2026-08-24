package de.makibytes.registerwerk.entra.web;

import java.util.List;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.web.dto.AuthConfigResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the sign-in configuration a single-page app needs before it bootstraps.
 *
 * <p>Deliberately lives in {@code entra} rather than next to {@code AuthController}: it reads
 * {@link RegisterwerkEntraProperties}, and {@code entra} already depends on {@code auth.api}, so
 * putting it in {@code auth} would close the cycle {@code auth → entra → auth} that
 * {@code ModulithArchitectureTest} rejects. The URL still sits under
 * {@code /api/v1/public/auth} because that is where a client would look for it — module
 * boundaries and URL layout need not agree.
 */
@RestController
@RequestMapping("/api/v1/public/auth")
public class AuthConfigController {

    private final RegisterwerkAuthProperties authProperties;
    private final RegisterwerkEntraProperties entraProperties;

    AuthConfigController(RegisterwerkAuthProperties authProperties, RegisterwerkEntraProperties entraProperties) {
        this.authProperties = authProperties;
        this.entraProperties = entraProperties;
    }

    /**
     * Unauthenticated by design — a client calls this precisely because it has no credentials
     * yet. It exposes only a public-client id and an authority URL, both of which appear in the
     * browser's address bar during any OIDC redirect. Kong caches it for 30 s.
     */
    @GetMapping("/config")
    public ResponseEntity<AuthConfigResponse> config() {
        boolean entra = authProperties.isEntraEnabled();
        return ResponseEntity.ok(new AuthConfigResponse(
            entra ? "ENTRA" : "LOCAL",
            entra ? entraProperties.getAuthority() : "",
            entra ? entraProperties.getSpaClientId() : "",
            entra && !entraProperties.getApiScope().isBlank()
                ? List.of(entraProperties.getApiScope())
                : List.of(),
            // In Entra mode the invite / password-reset endpoints throw, so hiding those links
            // is not cosmetic — it stops the SPA offering a flow that cannot succeed.
            !entra,
            entra && entraProperties.isSupportEnabled(),
            entra && entraProperties.isRequireTwoFactorEnrolment(),
            entraProperties.getMfaSetupUrl()
        ));
    }
}
