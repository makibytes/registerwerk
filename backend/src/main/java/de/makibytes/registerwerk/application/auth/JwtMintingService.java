package de.makibytes.registerwerk.application.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import de.makibytes.registerwerk.config.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.domain.entity.AppUser;
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
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .claim("entityId", user.getId().toString())
            .claim("roles", List.of(user.getRole()))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(tokenTtlSeconds))
            .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
