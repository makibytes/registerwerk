package de.makibytes.registerwerk.auth.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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

    /** Claim that says what a locally minted token may be used for. */
    public static final String CLAIM_USE = "use";
    /** {@code use} of a login / impersonation session token. */
    public static final String USE_SESSION = "session";
    /**
     * {@code use} of a dual-control approver token (the second approver's single-use approval of one
     * concrete request). Such a token is only ever valid in the {@code X-Dual-Control-Token} header: it is
     * minted for the approver, so presented as the caller's own Bearer it would run the request <em>as</em>
     * the approver (Wave 0a C1). Everything that authenticates a caller refuses it.
     */
    public static final String USE_DUAL_CONTROL = "dual_control";
    /** Audience every dual-control approver token carries in addition to the configured {@code JWT_AUDIENCE}. */
    public static final String DUAL_CONTROL_AUDIENCE = "registerwerk-dual-control";
    /** Scope claim of an approver token ({@code @RequiresStepUp(reason)} it approves). */
    public static final String CLAIM_STEPUP_SCOPE = "stepup_scope";
    /** The one user who may present an approver token minted from the approval queue (T8-02). */
    public static final String CLAIM_STEPUP_INITIATOR = "stepup_initiator";
    /** The approval-queue request an approver token was minted for (T8-02); informational. */
    public static final String CLAIM_STEPUP_REQUEST = "stepup_request";

    /**
     * True for a dual-control approver token - by marker, by audience, or (tokens minted before the marker
     * existed live for minutes) by the scope claim that only approver tokens ever carried.
     */
    public static boolean isDualControlApproverToken(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        if (USE_DUAL_CONTROL.equals(jwt.getClaimAsString(CLAIM_USE))) {
            return true;
        }
        List<String> audience = jwt.getAudience();
        if (audience != null && audience.contains(DUAL_CONTROL_AUDIENCE)) {
            return true;
        }
        return jwt.hasClaim(CLAIM_STEPUP_SCOPE);
    }

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
        List<String> audiences = new ArrayList<>();
        claims.forEach((name, value) -> {
            if ("aud".equals(name)) {
                // Token-specific audiences (dual-control approvals) are added to, never replace, JWT_AUDIENCE.
                if (value instanceof Collection<?> c) {
                    c.forEach(a -> audiences.add(String.valueOf(a)));
                } else if (value != null) {
                    audiences.add(String.valueOf(value));
                }
            } else if (value != null) {
                builder.claim(name, value);
            }
        });
        if (!audience.isBlank() && !audiences.contains(audience)) {
            audiences.add(0, audience);
        }
        if (!audiences.isEmpty()) {
            builder.audience(audiences);
        }
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** Random 128-bit token id (URL-safe), the revocation handle of a session token. */
    public static String newJti() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Mints a session token: carries a {@code jti} (logout revocation) and {@code use=session}. */
    public String mint(AppUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", user.getRoles().stream().map(Enum::name).toList());
        claims.put("email", user.getEmail());
        claims.put("name", user.getFullName());
        if (user.getLegalEntityId() != null) {
            claims.put("entityId", user.getLegalEntityId().toString());
            claims.put("entity_id", user.getLegalEntityId().toString());
        }
        claims.put("jti", newJti());
        claims.put(CLAIM_USE, USE_SESSION);
        return mintLocal(user.getId().toString(), tokenTtlSeconds, claims);
    }

    /**
     * Mints the token for an impersonation session once its one-time handoff code is exchanged.
     * Deliberately assigns only customer-side functional roles so operator-only endpoints stay
     * unreachable. {@code sub} remains the real admin's user id so audit attributes actions to
     * them; {@code jti} is the {@code impersonation_session} id, which the session guard checks on
     * every request. Expires with the session.
     */
    public String mintImpersonationToken(AppUser actor, ImpersonationSession session, long ttlSeconds) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", List.of("COMPANY_ADMIN", "ISSUER", "INVESTOR", "TRADER"));
        claims.put("email", actor.getEmail());
        claims.put("name", actor.getFullName() != null ? actor.getFullName() : actor.getEmail());
        claims.put("entityId", session.getTargetEntityId().toString());
        claims.put("entity_id", session.getTargetEntityId().toString());
        claims.put("imp", true);
        claims.put("imp_mode", session.getMode().name());
        claims.put("jti", session.getId().toString());
        claims.put(CLAIM_USE, USE_SESSION);
        return mintLocal(actor.getId().toString(), ttlSeconds, claims);
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
