package de.makibytes.registerwerk.stepup.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import de.makibytes.registerwerk.stepup.api.ClaimsChallengeException;
import de.makibytes.registerwerk.stepup.web.dto.ClaimsChallengeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format is not ours to choose — MSAL parses this header, so it has to match
 * Microsoft's claims-challenge specification exactly. These assertions are deliberately literal.
 */
@DisplayName("ClaimsChallengeAdvice")
class ClaimsChallengeAdviceTest {

    private final ClaimsChallengeAdvice advice = new ClaimsChallengeAdvice();

    @Test
    @DisplayName("responds 401 — not 403 — so the client re-authenticates rather than logging out")
    void respondsWithUnauthorized() {
        ResponseEntity<ClaimsChallengeResponse> response = handle();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("emits the exact WWW-Authenticate challenge MSAL expects")
    void emitsExpectedChallengeHeader() {
        String header = handle().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);

        assertThat(header).isEqualTo(
                "Bearer realm=\"\""
                + ", authorization_uri=\"https://login.microsoftonline.com/common/oauth2/authorize\""
                + ", error=\"insufficient_claims\""
                + ", claims=\"" + expectedClaimsBase64() + "\"");
        // realm must be empty whenever authorization_uri points at /common.
        assertThat(header).contains("realm=\"\"");
    }

    @Test
    @DisplayName("the claims parameter base64-decodes to the documented request object")
    void claimsParameterDecodesCorrectly() {
        String header = handle().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        String claims = header.replaceAll(".*claims=\"([^\"]+)\".*", "$1");

        String json = new String(Base64.getDecoder().decode(claims), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("{\"access_token\":{\"acrs\":{\"essential\":true,\"value\":\"c1\"}}}");
    }

    @Test
    @DisplayName("repeats the challenge in the body, in case a proxy strips the header")
    void bodyRepeatsTheChallenge() {
        ClaimsChallengeResponse body = handle().getBody();

        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("insufficient_claims");
        assertThat(body.claims()).isEqualTo(expectedClaimsBase64());
        assertThat(body.authorizationUri()).isEqualTo("https://login.microsoftonline.com/common/oauth2/authorize");
        assertThat(body.reason()).isEqualTo("FORCE_BURN_EWG26");
        assertThat(body.path()).isEqualTo("/api/v1/tokens/force-burn");
    }

    @Test
    @DisplayName("marks the response no-store so a challenge is never cached")
    void responseIsNotCacheable() {
        assertThat(handle().getHeaders().getCacheControl()).contains("no-store");
    }

    private ResponseEntity<ClaimsChallengeResponse> handle() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tokens/force-burn");
        HttpServletRequest servletRequest = request;
        return advice.handle(
                new ClaimsChallengeException(
                        "c1",
                        "https://login.microsoftonline.com/common/oauth2/authorize",
                        "FORCE_BURN_EWG26"),
                servletRequest);
    }

    private static String expectedClaimsBase64() {
        return Base64.getEncoder().encodeToString(
                "{\"access_token\":{\"acrs\":{\"essential\":true,\"value\":\"c1\"}}}"
                        .getBytes(StandardCharsets.UTF_8));
    }
}
