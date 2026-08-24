package de.makibytes.registerwerk.webhook.internal;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** HMAC-SHA256 request signing for outbound webhook deliveries, same primitive already used
 *  elsewhere in this codebase for token/step-up signing (see {@code StepUpTokenIssuer}). */
@Component
class WebhookSigningService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecureRandom random = new SecureRandom();

    String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex-encoded HMAC-SHA256 of {@code payload} keyed by {@code secret} — sent as the
     *  {@code X-Registerwerk-Signature} header so the receiver can verify authenticity. */
    String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }
}
