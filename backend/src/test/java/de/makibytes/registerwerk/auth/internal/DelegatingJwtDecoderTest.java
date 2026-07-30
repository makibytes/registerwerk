package de.makibytes.registerwerk.auth.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delegating decoder is what lets a single deployment run Entra sign-in for customers while
 * operators keep built-in HS256 login and local TOTP step-up. These tests pin the properties
 * that make that safe rather than merely convenient — above all that the two branches cannot
 * accept each other's tokens.
 */
@DisplayName("DelegatingJwtDecoder / local HS256 decoder")
class DelegatingJwtDecoderTest {

    private static final String DEV_SECRET = "unit-test-jwt-secret-not-for-production-use!!";

    private final UUID userId = UUID.randomUUID();
    private JwtMintingService minting;
    private JwtDecoder localDecoder;

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.setDevSecret(DEV_SECRET);
        props.setTokenTtlSeconds(3600L);
        minting = new JwtMintingService(props);
        localDecoder = JwtDecoderFactory.localHs256(DEV_SECRET);
    }

    @Test
    @DisplayName("a locally minted session token round-trips through the local decoder")
    void localToken_decodes() {
        Jwt decoded = localDecoder.decode(minting.mint(user()));

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtMintingService.LOCAL_ISSUER);
    }

    @Test
    @DisplayName("an HS256 token signed with the dev secret but a foreign issuer is rejected")
    void foreignIssuer_rejected() {
        // The scenario the issuer pin exists for: someone obtains JWT_DEV_SECRET from a lower
        // environment and signs their own token. Without the pin the signature alone sufficed.
        String forged = sign("https://evil.example.com", Instant.now(), 3600);

        assertThatThrownBy(() -> localDecoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired local token is rejected")
    void expiredToken_rejected() {
        String expired = sign(JwtMintingService.LOCAL_ISSUER, Instant.now().minusSeconds(7200), 60);

        assertThatThrownBy(() -> localDecoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("HS256 goes to the local decoder, anything else to the issuer decoder")
    void routesOnAlgorithm() {
        JwtDecoder issuerDecoder = token -> Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("sub", "from-issuer")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        DelegatingJwtDecoder delegating = new DelegatingJwtDecoder(localDecoder, issuerDecoder);

        assertThat(delegating.decode(minting.mint(user())).getSubject()).isEqualTo(userId.toString());
        assertThat(delegating.decode(rs256Shaped()).getSubject()).isEqualTo("from-issuer");
    }

    @Test
    @DisplayName("a non-HS256 token is refused outright when no OIDC issuer is configured")
    void noIssuerConfigured_rejectsRs256() {
        DelegatingJwtDecoder delegating = new DelegatingJwtDecoder(localDecoder, null);

        assertThatThrownBy(() -> delegating.decode(rs256Shaped()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("no OIDC issuer");
    }

    @Test
    @DisplayName("a malformed token surfaces as a JwtException, not a raw parse error")
    void malformedToken_handledAsJwtException() {
        DelegatingJwtDecoder delegating = new DelegatingJwtDecoder(localDecoder, null);

        assertThatThrownBy(() -> delegating.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an impersonation token also carries the local issuer, so it stays decodable")
    void impersonationToken_carriesLocalIssuer() {
        Jwt decoded = localDecoder.decode(minting.mintImpersonationToken(user(), UUID.randomUUID()));

        assertThat(decoded.getClaimAsBoolean("imp")).isTrue();
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtMintingService.LOCAL_ISSUER);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser user() {
        AppUser u = new AppUser();
        u.setId(userId);
        u.setEmail("unit@test.local");
        u.setFullName("Unit Test");
        u.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));
        return u;
    }

    private String sign(String issuer, Instant issuedAt, long ttlSeconds) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<SecurityContext>(DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject(userId.toString())
                        .claim("roles", List.of("REGISTRY_ADMIN"))
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plusSeconds(ttlSeconds))
                        .build())).getTokenValue();
    }

    /**
     * A structurally valid JWS declaring {@code alg=RS256} with a junk signature. Routing reads
     * the header only, so the signature never needs to verify for these assertions.
     */
    private static String rs256Shaped() {
        return base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}")
                + "." + base64Url("{\"sub\":\"someone\"}")
                + ".AAAA";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
