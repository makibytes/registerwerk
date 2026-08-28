package de.makibytes.registerwerk.auth.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the two decoders {@link DelegatingJwtDecoder} routes between, each pinned so that a
 * token accepted by one branch can never be accepted by the other.
 */
final class JwtDecoderFactory {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderFactory.class);

    private JwtDecoderFactory() {}

    /**
     * HS256 decoder for tokens this application minted itself.
     *
     * <p>Pinned to {@link JwtMintingService#LOCAL_ISSUER}. Without that pin, <em>any</em> HS256
     * token signed with {@code JWT_DEV_SECRET} would be accepted — including one an attacker
     * crafted after obtaining the secret from a lower environment, and including tokens minted
     * for a different purpose entirely. The issuer check costs nothing and turns the secret from
     * a universal forgery key into one scoped to this issuer.
     */
    static NimbusJwtDecoder localHs256(String devSecret, String audience) {
        SecretKey key = new SecretKeySpec(devSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(withAudience(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(JwtMintingService.LOCAL_ISSUER)), audience));
        return decoder;
    }

    /**
     * JWKS decoder for the configured OIDC issuer, with issuer, timestamp and — when configured
     * — audience validation.
     *
     * <p>The audience check is the important addition: Entra signs every token for a tenant with
     * the same keys, so without it an access token minted for <em>any other application</em> in
     * the tenant validates here and is accepted as a Registerwerk session.
     * {@code ProductionReadinessCheck} therefore requires a non-blank audience once Entra sign-in
     * is enabled; a blank one only logs a warning, so local experimentation still works.
     */
    static JwtDecoder issuerBacked(String issuerUri, RegisterwerkAuthProperties props) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuerUri);
        decoder.setJwtValidator(withAudience(defaults, props.getAudience()));
        if (props.getAudience().isBlank()) {
            log.warn("JWT_AUDIENCE is blank — audience validation is disabled");
        } else {
            log.info("JWT audience validation enabled for aud={}", props.getAudience());
        }
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> withAudience(OAuth2TokenValidator<Jwt> base, String audience) {
        if (audience == null || audience.isBlank()) {
            return base;
        }
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(base, audienceValidator);
    }
}
