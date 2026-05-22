package de.makibytes.registerwerk.auth.web;

import de.makibytes.registerwerk.auth.internal.AuthService;
import de.makibytes.registerwerk.auth.internal.AuthService.LoginResult;
import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/public/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResult result = authService.login(req.email(), req.password());
        long expiresAt = Instant.now().plusSeconds(result.ttlSeconds()).getEpochSecond();
        return ResponseEntity.ok(new LoginResponse(
            result.token(),
            "Bearer",
            result.userId().toString(),
            result.roles(),
            expiresAt
        ));
    }
}
