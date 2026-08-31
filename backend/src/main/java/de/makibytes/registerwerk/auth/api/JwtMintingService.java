package de.makibytes.registerwerk.auth.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JwtMintingService {

    /**
     * The {@code iss} stamped on every locally minted token (session, impersonation, step-up).
     * The HS256 decoder pins to it, so possession of {@code JWT_DEV_SECRET} alone is not enough
     * to forge a token this API will accept — it must also claim to come from here. Changing
     * this value invalidates every token currently in circulation.
     */
    public static final String LOCAL_ISSUER = "registerwerk-local";

    private final NimbusJwtEncoder encoder;
    private final long tokenTtlSeconds;
    private final String audience;

    public JwtMintingService(RegisterwerkAuthProperties props) {
        byte[] keyBytes = props.getDevSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(keyBytes));
        this.tokenTtlSeconds = props.getTokenTtlSeconds();
        this.audience = props.getAudience();
    }

    /**
     * The single place a registerwerk-issued HS256 token is assembled: stamps {@code iss}/{@code
     * aud}/{@code iat}/{@code exp} so the issuer pin ({@link #LOCAL_ISSUER}) and the audience pin
     * ({@code JWT_AUDIENCE}, consulted by both {@code JwtDecoderFactory} branches) can never drift
     * apart between token kinds — session, impersonation and step-up all mint through this.
     * {@code claims} values are skipped when null so callers don't need to pre-filter.
     */
    public String mintLocal(String subject, long ttlSeconds, Map<String, Object> claims) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
            .issuer(LOCAL_ISSUER)
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(ttlSeconds));
        claims.forEach((name, value) -> {
            if (value != null) {
                builder.claim(name, value);
            }
        });
        if (!audience.isBlank()) {
            builder.claim("aud", List.of(audience));
        }
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    public String mint(AppUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", user.getRoles().stream().map(Enum::name).toList());
        claims.put("email", user.getEmail());
        claims.put("name", user.getFullName());
        if (user.getLegalEntityId() != null) {
            claims.put("entityId", user.getLegalEntityId().toString());
            claims.put("entity_id", user.getLegalEntityId().toString());
        }
        return mintLocal(user.getId().toString(), tokenTtlSeconds, claims);
    }

    /**
     * Mints an impersonation token for a REGISTRY_ADMIN acting on behalf of a legal entity.
     * Deliberately assigns only customer-side functional roles so operator-only endpoints
     * remain unreachable during the impersonation session. The {@code sub} claim remains
     * the real admin's userId so audit events correctly attribute actions.
     */
    public String mintImpersonationToken(AppUser actor, UUID targetEntityId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", List.of("COMPANY_ADMIN", "ISSUER", "INVESTOR", "TRADER"));
        claims.put("email", actor.getEmail());
        claims.put("name", actor.getFullName() != null ? actor.getFullName() : actor.getEmail());
        claims.put("entityId", targetEntityId.toString());
        claims.put("entity_id", targetEntityId.toString());
        claims.put("imp", true);
        return mintLocal(actor.getId().toString(), tokenTtlSeconds, claims);
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
