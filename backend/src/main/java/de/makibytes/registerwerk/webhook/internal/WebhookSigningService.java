package de.makibytes.registerwerk.webhook.internal;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HMAC-SHA256 request signing for outbound webhook deliveries (5D-09).
 *
 * <p>Scheme {@code v1}: {@code signature = hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + body))}
 * where {@code timestamp} is the unix-seconds value of {@code X-Registerwerk-Timestamp} and
 * {@code deliveryId} the value of {@code X-Registerwerk-Delivery}. Binding the timestamp and delivery id
 * lets receivers enforce a replay window and dedupe; the previous body-only signature is gone. During a
 * secret-rotation overlap the header carries one {@code v1=} value per valid secret.
 */
@Component
class WebhookSigningService {

    static final String SCHEME = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecureRandom random = new SecureRandom();

    String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex HMAC over {@code timestamp.deliveryId.body}. */
    String sign(long timestamp, UUID deliveryId, String body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((timestamp + "." + deliveryId + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }

    /** The {@code X-Registerwerk-Signature} header value: {@code v1=<hex>[,v1=<hex>]}. */
    String signatureHeader(long timestamp, UUID deliveryId, String body, List<String> secrets) {
        return secrets.stream()
                .map(secret -> SCHEME + "=" + sign(timestamp, deliveryId, body, secret))
                .collect(Collectors.joining(","));
    }
}
