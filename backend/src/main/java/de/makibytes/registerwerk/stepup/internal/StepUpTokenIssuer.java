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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Issues short-lived step-up JWTs after RFC 6238 TOTP verification.
 * TOTP: HMAC-SHA1, 30-second window, 6 digits — Google Authenticator compatible.
 * If TOTP is not yet enrolled, any valid 6-digit code passes (dev/onboarding mode).
 * Enrolment: POST /api/v1/auth/step-up/enroll (generates secret + QR-code URL).
 */
@Component
public class StepUpTokenIssuer {

    private static final int STEP_UP_TTL_SECONDS = 600;
    private static final int TOTP_WINDOW = 1;     // ±1 step = ±30s tolerance
    private static final int TOTP_STEP_SECONDS = 30;
    private static final int TOTP_DIGITS = 6;

    private final AppUserRepository userRepository;
    private final NimbusJwtEncoder encoder;

    StepUpTokenIssuer(AppUserRepository userRepository, RegisterwerkAuthProperties props) {
        this.userRepository = userRepository;
        byte[] key = props.getDevSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    public String issueAfterVerification(UUID userId, String code, String method) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));

        if (code == null || !code.matches("\\d{6}")) {
            throw new AccessDeniedException("Invalid TOTP code. Provide a 6-digit code from your authenticator app.");
        }

        if (user.isTotpEnabled() && user.getTotpSecret() != null) {
            verifyTotpCode(user.getTotpSecret(), code);
        }
        // If TOTP not enrolled: any 6-digit code passes (dev/onboarding flow)

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("acr", "stepup")
                .claim("roles", user.getRoles().stream().map(Enum::name).toList())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(STEP_UP_TTL_SECONDS))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void verifyTotpCode(String base32Secret, String code) {
        long currentStep = Instant.now().getEpochSecond() / TOTP_STEP_SECONDS;
        for (int delta = -TOTP_WINDOW; delta <= TOTP_WINDOW; delta++) {
            if (generateTotp(base32Secret, currentStep + delta).equals(code)) return;
        }
        throw new AccessDeniedException("Invalid TOTP code. Check your authenticator app's time sync.");
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
