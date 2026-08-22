package de.makibytes.registerwerk.chain.internal;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mints a short-lived HS256 bearer token for chaincache's read APIs — the same
 * {@code NimbusJwtEncoder}/{@code JwsHeader}/{@code JwtClaimsSet} shape
 * {@code auth.api.JwtMintingService} already uses for Registerwerk's own tokens, but against a
 * <em>separate</em> signing key ({@code registerwerk.chaincache.jwt-secret}) — sharing
 * {@code JWT_DEV_SECRET} with chaincache would let anyone holding chaincache's secret forge a
 * Registerwerk operator token, and vice versa.
 *
 * <p>Deliberately stamps no {@code iss} claim: chaincache's {@code SecurityConfig} configures its
 * {@code NimbusJwtDecoder} with {@code withSecretKey(...).build()} — no issuer validator — so
 * adding one here would be asserting a contract chaincache doesn't actually check.
 * {@code roles: ["OPERATOR"]} maps to chaincache's {@code ROLE_OPERATOR} via its
 * {@code RolesClaimConverter} and satisfies every matcher this client touches
 * ({@code /api/**}, {@code /*}/api/**}, {@code /actuator/**}).
 */
@Component
class ChaincacheTokenFactory implements ChaincacheCredentials {

    private static final long TOKEN_TTL_SECONDS = 300;
    /** Re-minted once fewer than this many seconds remain — comfortably ahead of expiry so a
     *  request that's already in flight when the cached token is handed out never sees it lapse
     *  mid-request. */
    private static final long REFRESH_MARGIN_SECONDS = 60;

    private final NimbusJwtEncoder encoder;

    /** Null when {@code registerwerk.chaincache.jwt-secret} is unset — see {@link #bearerFor}. */
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    ChaincacheTokenFactory(@Value("${registerwerk.chaincache.jwt-secret:}") String secret) {
        this.encoder = secret == null || secret.isBlank() ? null
                : new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secret.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public Optional<String> bearerFor(String managementUrl) {
        if (encoder == null) {
            return Optional.empty();
        }
        CachedToken current = cached.get();
        Instant now = Instant.now();
        if (current != null && current.refreshAt().isAfter(now)) {
            return Optional.of(current.value());
        }
        String minted = mint(now);
        cached.set(new CachedToken(minted, now.plusSeconds(TOKEN_TTL_SECONDS - REFRESH_MARGIN_SECONDS)));
        return Optional.of(minted);
    }

    private String mint(Instant now) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("registerwerk")
                .claim("roles", List.of("OPERATOR"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(TOKEN_TTL_SECONDS))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private record CachedToken(String value, Instant refreshAt) {}
}
