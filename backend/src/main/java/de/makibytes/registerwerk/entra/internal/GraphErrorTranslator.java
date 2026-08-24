package de.makibytes.registerwerk.entra.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import de.makibytes.registerwerk.entra.api.EntraDirectoryException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a Graph error response into an {@link EntraDirectoryException} carrying the Graph error
 * code.
 *
 * <p>The code matters operationally: {@code Request_ResourceNotFound} means the user simply has
 * no such method (usually benign during a reset), while
 * {@code Authentication_RequestFromUnsupportedUserRole} means our service principal is missing
 * the Authentication Administrator role — a configuration fault an operator must fix. Collapsing
 * both into "Graph call failed" would make the second one very hard to diagnose.
 */
final class GraphErrorTranslator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraphErrorTranslator() {}

    static void translate(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);

        String code = null;
        String message = null;
        try {
            Map<?, ?> error = MAPPER.readValue(body, Map.class);
            Object inner = error.get("error");
            if (inner instanceof Map<?, ?> errorObject) {
                code = asString(errorObject.get("code"));
                message = asString(errorObject.get("message"));
            }
        } catch (RuntimeException e) {
            // Not the documented Graph error envelope (a gateway or proxy error page, say).
            // Fall through with what we have rather than masking the original failure.
        }

        String detail = message != null ? message : truncate(body);
        throw new EntraDirectoryException(
                "Microsoft Graph " + request.getMethod() + " " + request.getURI().getPath()
                        + " failed with HTTP " + status
                        + (code != null ? " (" + code + ")" : "") + ": " + detail,
                status, code, null);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty response body>";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
