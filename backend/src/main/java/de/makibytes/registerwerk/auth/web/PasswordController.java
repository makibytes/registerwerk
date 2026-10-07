package de.makibytes.registerwerk.auth.web;

import de.makibytes.registerwerk.auth.internal.PasswordChangeService;
import de.makibytes.registerwerk.auth.internal.SessionCookieService;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service password change. This is the only endpoint a {@code must_change_password} session may call
 * (every other one answers 403 {@code PASSWORD_CHANGE_REQUIRED}); on success the restricted token is revoked
 * and the response carries a fresh, unrestricted session (cookie + body).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordController {

    private final PasswordChangeService passwords;
    private final SessionCookieService cookies;

    PasswordController(PasswordChangeService passwords, SessionCookieService cookies) {
        this.passwords = passwords;
        this.cookies = cookies;
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                        @NotBlank @Size(min = 8, max = 200) String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<LoginResponse> change(@Valid @RequestBody ChangePasswordRequest request,
                                                Authentication auth) {
        Jwt jwt = auth.getPrincipal() instanceof Jwt j ? j : null;
        PasswordChangeService.Changed changed = passwords.change(SecurityUtils.extractUserId(auth),
                jwt != null ? jwt.getId() : null, jwt != null ? jwt.getExpiresAt() : null,
                request.currentPassword(), request.newPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.sessionCookie(changed.token()).toString())
                .body(new LoginResponse(
                        changed.user().getId().toString(),
                        changed.user().getRoles().stream().map(Enum::name).toList(),
                        changed.user().getEmail(),
                        changed.user().getFullName(),
                        changed.user().getLegalEntityId() != null ? changed.user().getLegalEntityId().toString() : null,
                        null, false, Instant.now().plusSeconds(changed.ttlSeconds()).getEpochSecond(), null, false));
    }
}
