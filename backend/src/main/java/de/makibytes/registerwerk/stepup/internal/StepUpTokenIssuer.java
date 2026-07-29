package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;

/**
 * Issues short-lived step-up JWTs after RFC 6238 TOTP verification.
 * TOTP: HMAC-SHA1, 30-second window, 6 digits — Google Authenticator compatible.
 * Enrolment: POST /api/v1/auth/step-up/enroll (generates secret + QR-code URL).
 *
 * <p>Hardening:
 * <ul>
 *   <li>Step-up is refused for users without TOTP enrolment — the step-up token
 *       gates dual-control actions (forced transfers, hit dismissal); issuing it
 *       without a second factor would void the Vieraugenprinzip. A dev-only
 *       escape hatch exists via {@code registerwerk.auth.step-up.allow-unenrolled}.</li>
 *   <li>Replay protection per RFC 6238 §5.2: each accepted code's time step is
 *       remembered; a code at or before the last accepted step is rejected.</li>
 *   <li>Brute-force throttle: 5 failed attempts lock step-up for 15 minutes.</li>
 *   <li>Constant-time code comparison.</li>
 * </ul>
 */
@Component
public class StepUpTokenIssuer {

    private static final int STEP_UP_TTL_SECONDS = 600;
    private static final int TOTP_WINDOW = 1;     // ±1 step = ±30s tolerance
    private static final int TOTP_STEP_SECONDS = 30;
    private static final int TOTP_DIGITS = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final int SECRET_BYTES = 20; // 160 bits — standard TOTP/HMAC-SHA1 secret strength
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final NimbusJwtEncoder encoder;
    private final boolean allowUnenrolled;

    /** userId → last accepted TOTP time step (replay protection, RFC 6238 §5.2). */
    private final Cache<UUID, Long> lastAcceptedStep = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    /** userId → failed verification attempts (brute-force throttle). */
    private final Cache<UUID, AtomicInteger> failedAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(100_000)
            .build();

    StepUpTokenIssuer(AppUserRepository userRepository, RegisterwerkAuthProperties props,
                      @Value("${registerwerk.auth.step-up.allow-unenrolled:false}") boolean allowUnenrolled) {
        this.userRepository = userRepository;
        this.allowUnenrolled = allowUnenrolled;
        byte[] key = props.getDevSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    public String issueAfterVerification(UUID userId, String code, String method) {
        return issueAfterVerification(userId, code, method, null);
    }

    /**
     * @param action the {@code @RequiresStepUp(reason=...)} value this token is intended to
     *               approve, if the caller knows it up front (e.g. minting a dual-control
     *               approval for one specific pending action). Embedded as a {@code
     *               stepup_scope} claim; null when the caller doesn't
     *               supply one, in which case the token carries no scope restriction.
     */
    public String issueAfterVerification(UUID userId, String code, String method, String action) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));

        if (code == null || !code.matches("\\d{6}")) {
            throw new AccessDeniedException("Invalid TOTP code. Provide a 6-digit code from your authenticator app.");
        }
        AtomicInteger attempts = failedAttempts.get(userId, k -> new AtomicInteger());
        if (attempts.get() >= MAX_ATTEMPTS) {
            throw new AccessDeniedException("Too many failed step-up attempts. Try again later.");
        }

        if (user.isTotpEnabled() && user.getTotpSecret() != null) {
            long acceptedStep = verifyTotpCode(userId, user.getTotpSecret(), code, attempts);
            lastAcceptedStep.put(userId, acceptedStep);
            failedAttempts.invalidate(userId);
        } else if (!allowUnenrolled) {
            // Without a second factor the step-up token is meaningless — refusing
            // here forces enrolment before any dual-control action can be approved.
            throw new AccessDeniedException(
                    "Step-up requires TOTP enrolment. Enrol an authenticator app first " +
                    "(POST /api/v1/auth/step-up/enroll).");
        }

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("acr", "stepup")
                .claim("roles", user.getRoles().stream().map(Enum::name).toList())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(STEP_UP_TTL_SECONDS));
        if (action != null && !action.isBlank()) {
            claimsBuilder.claim("stepup_scope", action);
        }
        return encoder.encode(JwtEncoderParameters.from(header, claimsBuilder.build())).getTokenValue();
    }

    /** Result of starting TOTP enrolment: the raw secret and an otpauth:// URI for a QR code. */
    public record EnrollmentStart(String secret, String otpauthUri) {}

    /**
     * Starts TOTP enrolment for the calling user — generates and stores a new secret, but
     * leaves {@code totpEnabled=false} until {@link #confirmEnrollment} verifies the caller's
     * authenticator app actually produced a matching code from it. Previously there was no
     * code path at all by which a real (non-seeded) user could ever enrol, meaning no
     * dual-control/step-up-gated action was reachable in a production deployment.
     *
     * @throws AccessDeniedException if this account is already enrolled (re-enrolling requires
     *         disenrolling first — not implemented here — so a caller who merely has a valid
     *         session JWT cannot silently overwrite an existing, working TOTP secret)
     */
    public EnrollmentStart enroll(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (user.isTotpEnabled()) {
            throw new AccessDeniedException(
                    "TOTP is already enrolled for this account. Disenrol before re-enrolling.");
        }
        String secret = generateBase32Secret();
        user.setTotpSecret(secret);
        userRepository.save(user);
        String otpauthUri = "otpauth://totp/Registerwerk:" + urlEncode(user.getEmail())
                + "?secret=" + secret + "&issuer=Registerwerk&algorithm=SHA1&digits=6&period=30";
        return new EnrollmentStart(secret, otpauthUri);
    }

    /**
     * Confirms TOTP enrolment: verifies a real code from the secret {@link #enroll} generated,
     * then activates it. Requiring one successful code (rather than activating immediately on
     * {@code enroll}) proves the user actually captured the secret in their authenticator app —
     * otherwise a typo'd/never-scanned QR code would silently brick step-up for that account.
     */
    public void confirmEnrollment(UUID userId, String code) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (user.getTotpSecret() == null) {
            throw new AccessDeniedException("No pending TOTP enrolment for this account — call enroll first.");
        }
        if (user.isTotpEnabled()) {
            throw new AccessDeniedException("TOTP is already enrolled for this account.");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new AccessDeniedException("Invalid TOTP code. Provide a 6-digit code from your authenticator app.");
        }
        long currentStep = Instant.now().getEpochSecond() / TOTP_STEP_SECONDS;
        boolean matched = false;
        for (int delta = -TOTP_WINDOW; delta <= TOTP_WINDOW; delta++) {
            if (constantTimeEquals(generateTotp(user.getTotpSecret(), currentStep + delta), code)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new AccessDeniedException("Invalid TOTP code. Check your authenticator app's time sync.");
        }
        user.setTotpEnabled(true);
        user.setTotpEnrolledAt(Instant.now());
        userRepository.save(user);
    }

    /** RFC 4226 §4: a shared secret at least 128 bits long; 160 bits matches HMAC-SHA1's block size. */
    static String generateBase32Secret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /** RFC 4648 Base32 encode (no padding) — the inverse of {@link #decodeBase32}. */
    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    /** @return the accepted time step (for replay tracking) */
    private long verifyTotpCode(UUID userId, String base32Secret, String code, AtomicInteger attempts) {
        long currentStep = Instant.now().getEpochSecond() / TOTP_STEP_SECONDS;
        Long lastUsed = lastAcceptedStep.getIfPresent(userId);
        for (int delta = -TOTP_WINDOW; delta <= TOTP_WINDOW; delta++) {
            long step = currentStep + delta;
            if (constantTimeEquals(generateTotp(base32Secret, step), code)) {
                // RFC 6238 §5.2: a code at or before the last accepted step is a replay.
                if (lastUsed != null && step <= lastUsed) {
                    attempts.incrementAndGet();
                    throw new AccessDeniedException(
                            "This TOTP code was already used. Wait for the next code.");
                }
                return step;
            }
        }
        attempts.incrementAndGet();
        throw new AccessDeniedException("Invalid TOTP code. Check your authenticator app's time sync.");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 6238 / RFC 4226 HOTP — generates a 6-digit code for the given time step. */
    public static String generateTotp(String base32Secret, long timeStep) {
        try {
            byte[] key = decodeBase32(base32Secret);
            byte[] data = ByteBuffer.allocate(8).putLong(timeStep).array();
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = hmac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                       | ((hash[offset + 1] & 0xFF) << 16)
                       | ((hash[offset + 2] & 0xFF) << 8)
                       | (hash[offset + 3] & 0xFF);
            return String.format("%0" + TOTP_DIGITS + "d", binary % (int) Math.pow(10, TOTP_DIGITS));
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    /** RFC 4648 Base32 decode — TOTP secrets are Base32-encoded (Google Authenticator standard). */
    public static byte[] decodeBase32(String encoded) {
        String upper = encoded.toUpperCase().replaceAll("[^A-Z2-7=]", "");
        int[] vals = upper.chars()
                .filter(c -> c != '=')
                .map(c -> c >= 'A' ? c - 'A' : c - '2' + 26)
                .toArray();
        byte[] result = new byte[vals.length * 5 / 8];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (int v : vals) {
            buffer = (buffer << 5) | (v & 0x1F);
            bitsLeft += 5;
            if (bitsLeft >= 8) { result[idx++] = (byte) (buffer >> (bitsLeft - 8)); bitsLeft -= 8; }
        }
        return Arrays.copyOf(result, idx);
    }
}
