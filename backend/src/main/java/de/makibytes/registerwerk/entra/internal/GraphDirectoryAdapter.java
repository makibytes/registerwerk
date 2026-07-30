package de.makibytes.registerwerk.entra.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import de.makibytes.registerwerk.entra.api.AuthContextRef;
import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.entra.api.EntraDirectoryException;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraUserMfaStatus;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.api.TemporaryAccessPass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Microsoft Graph adapter for authentication-method management.
 *
 * <p>Hand-rolled over {@code RestClient} rather than the {@code microsoft-graph} SDK: this needs
 * six endpoints, while the SDK pulls in Kiota's abstractions, four serializers, Graph core and a
 * second HTTP stack (OkHttp) alongside the JDK client fourteen existing adapters already share.
 *
 * <p>All identifiers are Entra object ids, never {@code app_user.id}.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.entra.support-enabled", havingValue = "true")
class GraphDirectoryAdapter implements EntraDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(GraphDirectoryAdapter.class);

    /** Graph refuses a TAP for an external guest; their UPN always contains this marker. */
    private static final String EXTERNAL_GUEST_UPN_MARKER = "#EXT#";

    private final RestClient client;
    private final RegisterwerkEntraProperties props;

    GraphDirectoryAdapter(
            RestClient.Builder builder,
            GraphAccessTokenProvider tokens,
            RegisterwerkEntraProperties props) {
        this.props = props;
        this.client = builder
                .baseUrl(props.getGraphBaseUrl())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.bearerToken());
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, GraphErrorTranslator::translate)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return props.isGraphConfigured();
    }

    @Override
    public EntraUserMfaStatus getMfaStatus(String entraObjectId) {
        List<EntraAuthMethod> methods;
        try {
            methods = listAuthMethods(entraObjectId);
        } catch (EntraDirectoryException e) {
            // A status read is informational. Reporting "unknown" keeps the page usable during a
            // Graph outage; a thrown error here would take the whole portal down with it.
            log.warn("Could not read authentication methods for oid={}: {}", entraObjectId, e.getMessage());
            return new EntraUserMfaStatus(true, EntraIdentityModel.WORKFORCE_MEMBER, false, List.of(),
                    null, "Two-factor status is temporarily unavailable.");
        }

        boolean registered = methods.stream().anyMatch(m -> m.type().isSecondFactor());
        return new EntraUserMfaStatus(true, classifyPrincipal(entraObjectId), registered, methods, Instant.now(), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<EntraAuthMethod> listAuthMethods(String entraObjectId) {
        Map<String, Object> response = client.get()
                .uri("/users/{id}/authentication/methods", entraObjectId)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> values = response == null
                ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("value", List.of());

        List<EntraAuthMethod> methods = new ArrayList<>(values.size());
        for (Map<String, Object> value : values) {
            EntraAuthMethodType type = EntraAuthMethodType.fromOdataType(asString(value.get("@odata.type")));
            methods.add(new EntraAuthMethod(
                    asString(value.get("id")),
                    type,
                    displayNameOf(value, type),
                    Boolean.TRUE.equals(value.get("isDefault")),
                    parseInstant(asString(value.get("createdDateTime")))));
        }
        return methods;
    }

    @Override
    public void deleteAuthMethod(String entraObjectId, EntraAuthMethodType type, String methodId) {
        if (!type.isDeletable()) {
            throw new IllegalArgumentException(
                    "Authentication method type " + type + " cannot be deleted individually.");
        }
        client.delete()
                .uri("/users/{id}/authentication/{collection}/{methodId}",
                        entraObjectId, type.collection(), methodId)
                .retrieve()
                .toBodilessEntity();
        log.info("Deleted Entra authentication method: oid={} type={} methodId={}",
                entraObjectId, type, methodId);
    }

    @Override
    public ResetOutcome resetAllAuthMethods(String entraObjectId) {
        List<EntraAuthMethod> deletable = listAuthMethods(entraObjectId).stream()
                .filter(m -> m.type().isDeletable())
                // Default method last. Entra is reported to refuse deleting the default while
                // others remain — undocumented, so it is ordered around rather than relied upon.
                .sorted(Comparator.comparing(EntraAuthMethod::isDefault))
                .toList();

        List<EntraAuthMethod> deleted = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (EntraAuthMethod method : deletable) {
            try {
                deleteAuthMethod(entraObjectId, method.type(), method.id());
                deleted.add(method);
            } catch (EntraDirectoryException e) {
                // Carry on: removing three of four factors still forces re-registration, and
                // aborting would leave the account in a worse, half-reset state than reporting
                // exactly which method resisted.
                failures.add(method.type() + " (" + method.id() + "): " + e.getMessage());
                log.warn("Could not delete method during reset: oid={} type={} — {}",
                        entraObjectId, method.type(), e.getMessage());
            }
        }

        log.info("Entra MFA reset for oid={}: {} deleted, {} failed",
                entraObjectId, deleted.size(), failures.size());
        return new ResetOutcome(deleted, failures);
    }

    @Override
    public void revokeSignInSessions(String entraObjectId) {
        client.post()
                .uri("/users/{id}/revokeSignInSessions", entraObjectId)
                .retrieve()
                .toBodilessEntity();
        log.info("Revoked Entra sign-in sessions for oid={}", entraObjectId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public TemporaryAccessPass issueTemporaryAccessPass(
            String entraObjectId, int lifetimeMinutes, boolean usableOnce) {

        Instant start = Instant.now();
        Map<String, Object> response = client.post()
                .uri("/users/{id}/authentication/temporaryAccessPassMethods", entraObjectId)
                .body(Map.of(
                        "startDateTime", start.toString(),
                        "lifetimeInMinutes", lifetimeMinutes,
                        "isUsableOnce", usableOnce))
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new EntraDirectoryException(
                    "Graph returned an empty body when issuing a Temporary Access Pass.", 502, null, null);
        }

        // Deliberately never logged, not even at DEBUG: this value authenticates as the user.
        String pass = asString(response.get("temporaryAccessPass"));
        int lifetime = response.get("lifetimeInMinutes") instanceof Number n
                ? n.intValue()
                : lifetimeMinutes;
        Instant startAt = parseInstant(asString(response.get("startDateTime")));
        if (startAt == null) {
            startAt = start;
        }

        log.info("Issued Temporary Access Pass for oid={} lifetimeMinutes={} usableOnce={}",
                entraObjectId, lifetime, usableOnce);

        return new TemporaryAccessPass(
                asString(response.get("id")),
                pass,
                startAt,
                startAt.plusSeconds(lifetime * 60L),
                lifetime,
                Boolean.TRUE.equals(response.get("isUsableOnce")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AuthContextRef> listAuthenticationContexts() {
        Map<String, Object> response = client.get()
                .uri("/identity/conditionalAccess/authenticationContextClassReferences")
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> values = response == null
                ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("value", List.of());

        return values.stream()
                .map(v -> new AuthContextRef(
                        asString(v.get("id")),
                        asString(v.get("displayName")),
                        Boolean.TRUE.equals(v.get("isAvailable"))))
                .toList();
    }

    /**
     * Whether this principal is a member or a guest, and — for guests — whether they are
     * external. It matters because Entra will not issue a Temporary Access Pass to an external
     * guest, so the console must disable that action rather than let it fail confusingly.
     */
    @SuppressWarnings("unchecked")
    @Override
    public EntraIdentityModel classifyPrincipal(String entraObjectId) {
        try {
            Map<String, Object> user = client.get()
                    .uri("/users/{id}?$select=id,userType,userPrincipalName,externalUserState", entraObjectId)
                    .retrieve()
                    .body(Map.class);
            if (user == null) {
                return EntraIdentityModel.WORKFORCE_MEMBER;
            }
            boolean guest = "Guest".equalsIgnoreCase(asString(user.get("userType")));
            return guest ? EntraIdentityModel.WORKFORCE_GUEST : EntraIdentityModel.WORKFORCE_MEMBER;
        } catch (EntraDirectoryException e) {
            log.warn("Could not classify Entra principal oid={}: {}", entraObjectId, e.getMessage());
            return EntraIdentityModel.WORKFORCE_MEMBER;
        }
    }

    /** True when this principal is an <em>external</em> B2B guest, who cannot hold a TAP. */
    @SuppressWarnings("unchecked")
    @Override
    public boolean isExternalGuest(String entraObjectId) {
        Map<String, Object> user = client.get()
                .uri("/users/{id}?$select=userType,userPrincipalName", entraObjectId)
                .retrieve()
                .body(Map.class);
        if (user == null) {
            return false;
        }
        String upn = asString(user.get("userPrincipalName"));
        return "Guest".equalsIgnoreCase(asString(user.get("userType")))
                && upn != null && upn.contains(EXTERNAL_GUEST_UPN_MARKER);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Graph names the label differently per method type; pick whichever the payload carries. */
    private static String displayNameOf(Map<String, Object> value, EntraAuthMethodType type) {
        return switch (type) {
            case PHONE -> asString(value.get("phoneNumber"));
            case EMAIL -> asString(value.get("emailAddress"));
            default -> {
                String name = asString(value.get("displayName"));
                yield name != null ? name : asString(value.get("model"));
            }
        };
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
