package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import de.makibytes.registerwerk.stepup.events.TotpLifecycleEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues short-lived step-up JWTs after RFC 6238 TOTP verification.
 * TOTP: HMAC-SHA1, 30-second window, 6 digits — Google Authenticator compatible.
 *
 * <p>Hardening (K3, 6-09):
 * <ul>
 *   <li>Step-up is refused for users without TOTP enrolment — the step-up token gates dual-control
 *       actions, and issuing it without a second factor would void the Vieraugenprinzip. A dev-only
 *       escape hatch exists via {@code registerwerk.auth.step-up.allow-unenrolled}.</li>
 *   <li>No trust-on-first-use: starting an enrolment requires the account's current password, so a
 *       stolen or left-open session alone cannot bind an attacker's authenticator. Disenrolment needs a
 *       valid current code; an operator reset needs step-up plus a second approver.</li>
 *   <li>The secret is envelope-encrypted with the platform KEK ({@link TotpSecretStore}).</li>
 *   <li>Replay protection (RFC 6238 §5.2) and the brute-force lockout (5 failures lock 15 minutes) live in
 *       {@code totp_state}, so every replica enforces the same state; the check is one atomic
 *       {@code UPDATE ... WHERE last_accepted_step < ?}.</li>
 *   <li>Constant-time code comparison.</li>
 * </ul>
 */
@Component
public class StepUpTokenIssuer {

    private static final int STEP_UP_TTL_SECONDS = 600;
    private static final int TOTP_WINDOW = 1;     // ±1 step = ±30s tolerance
    private static final int TOTP_STEP_SECONDS = 30;
    private static final int TOTP_DIGITS = 6;
    private static final int SECRET_BYTES = 20; // 160 bits — standard TOTP/HMAC-SHA1 secret strength
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final JwtMintingService jwtMintingService;
    private final TotpSecretStore secrets;
    private final TotpStateRepository state;
    private final PasswordEncoder passwordEncoder;
    private final DualControlProperties dualControl;
    private final ApplicationEventPublisher events;
    private final boolean allowUnenrolled;

    StepUpTokenIssuer(AppUserRepository userRepository, JwtMintingService jwtMintingService,
                      TotpSecretStore secrets, TotpStateRepository state, PasswordEncoder passwordEncoder,
                      DualControlProperties dualControl, ApplicationEventPublisher events,
                      @Value("${registerwerk.auth.step-up.allow-unenrolled:false}") boolean allowUnenrolled) {
        this.userRepository = userRepository;
        this.jwtMintingService = jwtMintingService;
        this.secrets = secrets;
        this.state = state;
        this.passwordEncoder = passwordEncoder;
        this.dualControl = dualControl;
        this.events = events;
        this.allowUnenrolled = allowUnenrolled;
    }

    public String issueAfterVerification(UUID userId, String code, String method) {
        return issueAfterVerification(userId, code, method, null, null, null);
    }

    public String issueAfterVerification(UUID userId, String code, String method, String action) {
        return issueAfterVerification(userId, code, method, action, null, null);
    }

    /**
     * @param action     the {@code @RequiresStepUp(reason=...)} value this token is intended to approve, when
     *                   minting a dual-control approval. Embedded as {@code stepup_scope}; null for a plain
     *                   step-up token.
     * @param target     {@code "METHOD /path[?query]"} of the exact request being approved. Required whenever
     *                   {@code action} is set and target binding is on (6-08); embedded as {@code stepup_target}.
     * @param targetBody the JSON body of that request, for reasons whose approval also covers the body
     *                   (mint, burn, forced transfer); null otherwise
     */
    public String issueAfterVerification(UUID userId, String code, String method, String action,
                                         String target, JsonNode targetBody) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));

        if (code == null || !code.matches("\\d{6}")) {
            throw new AccessDeniedException("Invalid TOTP code. Provide a 6-digit code from your authenticator app.");
        }
        boolean approval = action != null && !action.isBlank();
        String targetDigest = null;
        if (approval && dualControl.bindsTarget(action)) {
            if (target == null || target.isBlank()) {
                throw new AccessDeniedException(
                        "A dual-control approval must name the request it approves ('target': 'METHOD /path').");
            }
            String canonicalBody = null;
            if (dualControl.bindsBody(action)) {
                canonicalBody = targetBody == null || targetBody.isNull() ? "" : DualControlTarget.canonicalJson(targetBody);
            }
            try {
                targetDigest = DualControlTarget.digestOfTarget(target, canonicalBody);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException(e.getMessage());
            }
        }

        if (user.isTotpEnabled() && user.getTotpSecret() != null) {
            verifyAndAdvance(user, code);
        } else if (!allowUnenrolled) {
            // Without a second factor the step-up token is meaningless — refusing
            // here forces enrolment before any dual-control action can be approved.
            throw new AccessDeniedException(
                    "Step-up requires TOTP enrolment. Enrol an authenticator app first " +
                    "(POST /api/v1/auth/step-up/enroll).");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("acr", "stepup");
        claims.put("roles", user.getRoles().stream().map(Enum::name).toList());
        claims.put("email", user.getEmail());
        long ttl = STEP_UP_TTL_SECONDS;
        if (approval) {
            claims.put("stepup_scope", action);
            if (targetDigest != null) {
                claims.put("stepup_target", targetDigest);
            }
            // Single-use handle; the token only ever lives for the dual-control window.
            claims.put("jti", JwtMintingService.newJti());
            ttl = Math.min(ttl, dualControl.getWindowSeconds());
        }
        return jwtMintingService.mintLocal(userId.toString(), ttl, claims);
    }

    /** Result of starting TOTP enrolment: the raw secret and an otpauth:// URI for a QR code. */
    public record EnrollmentStart(String secret, String otpauthUri) {}

    /**
     * Starts TOTP enrolment for the calling user — generates and stores a new secret (encrypted), but
     * leaves {@code totpEnabled=false} until {@link #confirmEnrollment} verifies the caller's
     * authenticator app actually produced a matching code from it.
     *
     * <p>No trust-on-first-use (6-09): the account's current password must be presented again. Failed
     * password attempts count against the same lockout as wrong TOTP codes.
     *
     * @throws AccessDeniedException if the password is wrong, the account is IdP-managed, or it is already
     *         enrolled (use {@link #disenroll} or an operator reset first)
     */
    @Transactional
    public EnrollmentStart enroll(UUID userId, String currentPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (user.getAuthProvider() != UserAuthProvider.LOCAL) {
            throw new AccessDeniedException(
                    "Second factor for this account is managed by your identity provider.");
        }
        if (state.isLocked(userId)) {
            throw new AccessDeniedException("Too many failed attempts. Try again later.");
        }
        if (currentPassword == null || currentPassword.isEmpty() || user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            state.recordFailure(userId);
            throw new AccessDeniedException("Current password is required to enrol an authenticator.");
        }
        if (user.isTotpEnabled()) {
            throw new AccessDeniedException(
                    "TOTP is already enrolled for this account. Disenrol before re-enrolling.");
        }
        String secret = generateBase32Secret();
        user.setTotpSecret(secrets.encrypt(userId, secret));
        user.setTotpSecretKid(secrets.kid());
        userRepository.save(user);
        events.publishEvent(new TotpLifecycleEvent("ENROLMENT_STARTED", userId, userId, null, null));
        String otpauthUri = "otpauth://totp/Registerwerk:" + urlEncode(user.getEmail())
                + "?secret=" + secret + "&issuer=Registerwerk&algorithm=SHA1&digits=6&period=30";
        return new EnrollmentStart(secret, otpauthUri);
    }

    /**
     * Confirms TOTP enrolment: verifies a real code from the secret {@link #enroll} generated,
     * then activates it. Requiring one successful code proves the user actually captured the secret
     * in their authenticator app. The accepted step is recorded, so that same code cannot be replayed
     * as a step-up code.
     */
    @Transactional
    public void confirmEnrollment(UUID userId, String code) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (user.getTotpSecret() == null) {
            throw new AccessDeniedException("No pending TOTP enrolment for this account — call enroll first.");
        }
        if (user.isTotpEnabled()) {
            throw new AccessDeniedException("TOTP is already enrolled for this account.");
        }
        verifyAndAdvance(user, code);
        user.setTotpEnabled(true);
        user.setTotpEnrolledAt(Instant.now());
        userRepository.save(user);
        events.publishEvent(new TotpLifecycleEvent("ENROLLED", userId, userId, null, null));
    }

    /**
     * Self-service disenrolment; requires a valid current TOTP code (replay-protected, lockout-counted).
     * Revokes the user's live sessions, since the account's assurance level just changed.
     */
    @Transactional
    public void disenroll(UUID userId, String code) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (!user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new AccessDeniedException("No TOTP enrolment to remove for this account.");
        }
        verifyAndAdvance(user, code);
        clearEnrolment(user);
        events.publishEvent(new TotpLifecycleEvent("DISENROLLED", userId, userId, null, null));
    }

    /**
     * Operator reset of another user's TOTP (lost device). The caller must already have passed step-up and
     * a second approver (enforced on the endpoint). The user re-enrols at next login; live sessions end.
     */
    @Transactional
    public void reset(UUID targetUserId, UUID actorId, String actorRole, UUID approverId) {
        if (targetUserId.equals(actorId)) {
            throw new AccessDeniedException("Use self-service disenrolment to remove your own authenticator.");
        }
        AppUser user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", targetUserId));
        clearEnrolment(user);
        events.publishEvent(new TotpLifecycleEvent("RESET", targetUserId, actorId, actorRole, approverId));
    }

    private void clearEnrolment(AppUser user) {
        user.setTotpSecret(null);
        user.setTotpSecretKid(null);
        user.setTotpEnabled(false);
        user.setTotpEnrolledAt(null);
        user.revokeSessions();
        userRepository.save(user);
        state.reset(user.getId());
    }

    /**
     * Verifies {@code code} against the user's secret and consumes its time step in the shared state.
     * Wrong codes and replays count towards the lockout.
     */
    private void verifyAndAdvance(AppUser user, String code) {
        UUID userId = user.getId();
        if (code == null || !code.matches("\\d{6}")) {
            throw new AccessDeniedException("Invalid TOTP code. Provide a 6-digit code from your authenticator app.");
        }
        if (state.isLocked(userId)) {
            throw new AccessDeniedException("Too many failed step-up attempts. Try again later.");
        }
        String stored = user.getTotpSecret();
        String secret = secrets.decrypt(userId, stored);
        long currentStep = Instant.now().getEpochSecond() / TOTP_STEP_SECONDS;
        long matched = -1;
        for (int delta = -TOTP_WINDOW; delta <= TOTP_WINDOW; delta++) {
            if (constantTimeEquals(generateTotp(secret, currentStep + delta), code) && matched < 0) {
                matched = currentStep + delta;
            }
        }
        if (matched < 0) {
            state.recordFailure(userId);
            throw new AccessDeniedException("Invalid TOTP code. Check your authenticator app's time sync.");
        }
        if (!state.acceptStep(userId, matched)) {
            // RFC 6238 §5.2: a code at or before the last accepted step is a replay.
            state.recordFailure(userId);
            throw new AccessDeniedException("This TOTP code was already used. Wait for the next code.");
        }
        if (!secrets.isEncrypted(stored)) {
            // Lazy migration of a pre-V35 plaintext secret.
            user.setTotpSecret(secrets.encrypt(userId, secret));
            user.setTotpSecretKid(secrets.kid());
            userRepository.save(user);
        }
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
