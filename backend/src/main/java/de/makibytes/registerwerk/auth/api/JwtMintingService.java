package de.makibytes.registerwerk.auth.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class JwtMintingService {

    private final NimbusJwtEncoder encoder;
    private final long tokenTtlSeconds;

    public JwtMintingService(RegisterwerkAuthProperties props) {
        byte[] keyBytes = props.getDevSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(keyBytes));
        this.tokenTtlSeconds = props.getTokenTtlSeconds();
    }

    public String mint(AppUser user) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        List<String> roles = user.getRoles().stream().map(Enum::name).toList();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .claim("roles", roles)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(tokenTtlSeconds));
        if (user.getEmail() != null) {
            claims.claim("email", user.getEmail());
        }
        if (user.getFullName() != null) {
            claims.claim("name", user.getFullName());
        }
        if (user.getLegalEntityId() != null) {
            claims = JwtClaimsSet.from(claims.build())
                .claim("entityId", user.getLegalEntityId().toString())
                .claim("entity_id", user.getLegalEntityId().toString())
                ;
        }
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /**
     * Mints an impersonation token for a REGISTRY_ADMIN acting on behalf of a legal entity.
     * Deliberately assigns only customer-side functional roles so operator-only endpoints
     * remain unreachable during the impersonation session. The {@code sub} claim remains
     * the real admin's userId so audit events correctly attribute actions.
     */
    public String mintImpersonationToken(AppUser actor, UUID targetEntityId) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(actor.getId().toString())
            .claim("roles", List.of("COMPANY_ADMIN", "ISSUER", "INVESTOR", "TRADER"))
            .claim("email", actor.getEmail())
            .claim("name", actor.getFullName() != null ? actor.getFullName() : actor.getEmail())
            .claim("entityId", targetEntityId.toString())
            .claim("entity_id", targetEntityId.toString())
            .claim("imp", true)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(tokenTtlSeconds))
            .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
