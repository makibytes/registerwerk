package de.makibytes.registerwerk.auth.internal;

import java.text.ParseException;

import com.nimbusds.jose.JWSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Routes a bearer token to the decoder that can actually verify it, based on the JWS
 * {@code alg} header: {@code HS256} means Registerwerk minted it locally, anything else means
 * it came from the OIDC issuer and must be checked against the JWKS.
 *
 * <p><strong>Why this exists.</strong> Both portals hit the same URLs
 * ({@code /api/v1/wallets}, {@code /api/v1/holder-blocks}, …), so path-scoped
 * {@code SecurityFilterChain}s cannot separate them. With only one decoder bean, turning on
 * Entra sign-in made the operator portal — which keeps built-in HS256 login and local TOTP
 * step-up — completely unusable, and made every self-minted step-up token unverifiable.
 *
 * <p><strong>Why routing on {@code alg} is safe.</strong> The header is unauthenticated input,
 * so it must not be trusted for anything but decoder <em>selection</em>. It isn't: each branch
 * then performs full signature and claim validation. An attacker who flips {@code alg} to
 * {@code RS256} merely gets their token checked against the tenant's JWKS, where it fails. The
 * one genuine risk is cross-acceptance — a token intended for one branch being honoured by the
 * other — which is why both decoders are issuer-pinned and the Entra branch is also
 * audience-pinned. See {@link JwtDecoderFactory}.
 */
class DelegatingJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(DelegatingJwtDecoder.class);

    private static final String HS256 = "HS256";

    private final JwtDecoder localDecoder;
    private final JwtDecoder issuerDecoder;

    /**
     * @param localDecoder  HS256 decoder for locally minted session, impersonation and
     *                      step-up tokens; never null
     * @param issuerDecoder JWKS decoder for the configured OIDC issuer, or null when no
     *                      issuer is configured (local-only deployment)
     */
    DelegatingJwtDecoder(JwtDecoder localDecoder, JwtDecoder issuerDecoder) {
        this.localDecoder = localDecoder;
        this.issuerDecoder = issuerDecoder;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String algorithm;
        try {
            algorithm = JWSObject.parse(token).getHeader().getAlgorithm().getName();
        } catch (ParseException | IllegalArgumentException | NullPointerException e) {
            // Malformed, or not a JWS at all. Hand it to the local decoder so the caller gets
            // the same error they would have got before this class existed.
            return localDecoder.decode(token);
        }

        if (HS256.equals(algorithm)) {
            return localDecoder.decode(token);
        }
        if (issuerDecoder == null) {
            log.debug("Rejecting {} token: no OIDC issuer configured (JWT_ISSUER_URI is blank)", algorithm);
            throw new BadJwtException(
                    "Token is signed with " + algorithm + " but no OIDC issuer is configured.");
        }
        return issuerDecoder.decode(token);
    }
}
