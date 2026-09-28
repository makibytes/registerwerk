package de.makibytes.registerwerk;

import de.makibytes.registerwerk.auth.api.JwtMintingService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Mints HS256 tokens accepted by the local (dev-secret) decoder in integration tests. */
public final class TestJwt {

    private TestJwt() {}

    /**
     * @param stepUp   adds {@code acr=stepup} (fresh {@code iat}) for {@code @RequiresStepUp}
     * @param scope    {@code stepup_scope} claim (the action reason) for a dual-control token, or null
     * @param entityId {@code entity_id} claim for customer-side callers, or null
     */
    public static String mint(String secret, UUID sub, boolean stepUp, String scope, UUID entityId, String... roles) {
        try {
            long iat = Instant.now().getEpochSecond();
            String rolesJson = String.join(",", Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
            String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = b64("{"
                    + "\"iss\":\"" + JwtMintingService.LOCAL_ISSUER + "\","
                    + "\"sub\":\"" + sub + "\","
                    + "\"roles\":[" + rolesJson + "],"
                    + (stepUp ? "\"acr\":\"stepup\"," : "")
                    + (scope != null ? "\"stepup_scope\":\"" + scope + "\"," : "")
                    + (entityId != null ? "\"entity_id\":\"" + entityId + "\"," : "")
                    + "\"iat\":" + iat + ","
                    + "\"exp\":" + (iat + 3600)
                    + "}");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signingInput = header + "." + payload;
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
