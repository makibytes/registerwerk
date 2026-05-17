package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtMintingService unit tests")
class JwtMintingServiceTest {

    private JwtMintingService mintingService;
    private NimbusJwtDecoder verifier;
    private static final String DEV_SECRET = "registerwerk-dev-jwt-secret-change-in-production!!";

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.setDevSecret(DEV_SECRET);
        props.setTokenTtlSeconds(28800L);
        mintingService = new JwtMintingService(props);

        SecretKey key = new SecretKeySpec(DEV_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        verifier = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Test
    @DisplayName("Minted token round-trips through the resource-server decoder")
    void mintedToken_validatesWithDevDecoder() {
        AppUser user = buildUser();
        String tokenValue = mintingService.mint(user);

        Jwt decoded = verifier.decode(tokenValue);

        assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
        assertThat(decoded.getClaimAsString("entityId")).isNull();
        assertThat(decoded.<List<String>>getClaim("roles")).containsExactly("REGISTRY_ADMIN");
        assertThat(decoded.getClaimAsString("email")).isEqualTo("admin@local");
        assertThat(decoded.getIssuedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AppUser buildUser() {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@local");
        user.setRole(AppUserRole.REGISTRY_ADMIN);
        user.setEnabled(true);
        return user;
    }
}
