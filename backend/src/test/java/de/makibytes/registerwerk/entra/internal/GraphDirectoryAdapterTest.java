package de.makibytes.registerwerk.entra.internal;

import java.util.List;

import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.entra.api.EntraDirectoryException;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraUserMfaStatus;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.api.TemporaryAccessPass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Graph is stubbed with {@code MockRestServiceServer}, which binds to the same
 * {@code RestClient.Builder} the adapter is constructed with — no WireMock, no extra dependency,
 * and no network.
 */
@DisplayName("GraphDirectoryAdapter")
class GraphDirectoryAdapterTest {

    private static final String BASE = "https://graph.microsoft.com/v1.0";
    private static final String OID = "11111111-1111-1111-1111-111111111111";

    private MockRestServiceServer server;
    private GraphDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        GraphAccessTokenProvider tokens = mock(GraphAccessTokenProvider.class);
        when(tokens.bearerToken()).thenReturn("stub-graph-token");

        RegisterwerkEntraProperties props = new RegisterwerkEntraProperties();
        props.setGraphBaseUrl(BASE);
        props.setTenantId("22222222-2222-2222-2222-222222222222");
        props.setClientId("api-client");
        props.setClientSecret("secret");
        props.setSupportEnabled(true);

        adapter = new GraphDirectoryAdapter(builder, tokens, props);
    }

    @Test
    @DisplayName("maps the methods payload, including which one is the default")
    void listAuthMethods_mapsPayload() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(methodsPayload(), MediaType.APPLICATION_JSON));

        List<EntraAuthMethod> methods = adapter.listAuthMethods(OID);

        assertThat(methods).hasSize(3);
        assertThat(methods.get(0).type()).isEqualTo(EntraAuthMethodType.MICROSOFT_AUTHENTICATOR);
        assertThat(methods.get(0).isDefault()).isTrue();
        assertThat(methods.get(1).type()).isEqualTo(EntraAuthMethodType.PHONE);
        assertThat(methods.get(1).displayName()).isEqualTo("+49 170 *******89");
        assertThat(methods.get(2).type()).isEqualTo(EntraAuthMethodType.PASSWORD);
        server.verify();
    }

    @Test
    @DisplayName("a password alone does not count as a registered second factor")
    void getMfaStatus_passwordOnly_isNotRegistered() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andRespond(withSuccess("""
                        {"value":[{"@odata.type":"#microsoft.graph.passwordAuthenticationMethod","id":"p"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/users/" + OID + "?$select=id,userType,userPrincipalName,externalUserState"))
                .andRespond(withSuccess("{\"userType\":\"Member\"}", MediaType.APPLICATION_JSON));

        EntraUserMfaStatus status = adapter.getMfaStatus(OID);

        assertThat(status.applicable()).isTrue();
        assertThat(status.registered()).isFalse();
    }

    @Test
    @DisplayName("a Graph failure degrades the status read to 'unavailable' rather than throwing")
    void getMfaStatus_graphDown_reportsUnavailable() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        EntraUserMfaStatus status = adapter.getMfaStatus(OID);

        // A status read that cannot run is "unknown". Throwing would let a Graph outage take the
        // whole customer portal down with it.
        assertThat(status.applicable()).isTrue();
        assertThat(status.registered()).isFalse();
        assertThat(status.message()).contains("temporarily unavailable");
    }

    @Test
    @DisplayName("reset deletes the default method last and skips non-deletable ones")
    void resetAllAuthMethods_deletesDefaultLast() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andRespond(withSuccess(methodsPayload(), MediaType.APPLICATION_JSON));
        // Ordering is asserted by the order of these expectations: MockRestServiceServer is
        // strict by default, so the phone (non-default) must be deleted before the authenticator.
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/phoneMethods/phone-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/users/" + OID
                        + "/authentication/microsoftAuthenticatorMethods/auth-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        EntraDirectoryPort.ResetOutcome outcome = adapter.resetAllAuthMethods(OID);

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.deleted()).hasSize(2);
        // The password is not a removable factor and must not have been attempted.
        assertThat(outcome.deleted()).noneMatch(m -> m.type() == EntraAuthMethodType.PASSWORD);
        server.verify();
    }

    @Test
    @DisplayName("reset continues past a method it cannot delete and reports the failure")
    void resetAllAuthMethods_collectsFailures() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andRespond(withSuccess(methodsPayload(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/phoneMethods/phone-1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/users/" + OID
                        + "/authentication/microsoftAuthenticatorMethods/auth-1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":{\"code\":\"Authentication_NotAllowed\",\"message\":\"default method\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        EntraDirectoryPort.ResetOutcome outcome = adapter.resetAllAuthMethods(OID);

        // Removing one of two factors still forces re-registration; aborting would leave the
        // account half-reset with no record of which method resisted.
        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.deleted()).hasSize(1);
        assertThat(outcome.failures()).singleElement().asString()
                .contains("MICROSOFT_AUTHENTICATOR")
                .contains("Authentication_NotAllowed");
    }

    @Test
    @DisplayName("refuses to delete a method type Graph has no collection for")
    void deleteAuthMethod_nonDeletableType_rejected() {
        assertThatThrownBy(() -> adapter.deleteAuthMethod(OID, EntraAuthMethodType.PASSWORD, "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maps a Temporary Access Pass, carrying the value through exactly once")
    void issueTemporaryAccessPass_mapsResponse() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/temporaryAccessPassMethods"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.lifetimeInMinutes").value(60))
                .andExpect(jsonPath("$.isUsableOnce").value(true))
                .andRespond(withSuccess("""
                        {
                          "id": "tap-1",
                          "temporaryAccessPass": "+drkzqAD",
                          "startDateTime": "2026-07-30T10:00:00Z",
                          "lifetimeInMinutes": 60,
                          "isUsableOnce": true
                        }
                        """, MediaType.APPLICATION_JSON));

        TemporaryAccessPass tap = adapter.issueTemporaryAccessPass(OID, 60, true);

        assertThat(tap.id()).isEqualTo("tap-1");
        assertThat(tap.value()).isEqualTo("+drkzqAD");
        assertThat(tap.usableOnce()).isTrue();
        assertThat(tap.expiresAt()).isEqualTo(tap.startAt().plusSeconds(3600));
        server.verify();
    }

    @Test
    @DisplayName("toString redacts the pass, so an incidental log line cannot leak it")
    void temporaryAccessPass_toStringIsRedacted() {
        TemporaryAccessPass tap = new TemporaryAccessPass(
                "tap-1", "+drkzqAD", java.time.Instant.EPOCH, java.time.Instant.EPOCH, 60, true);

        String rendered = tap.toString();

        assertThat(rendered).doesNotContain("+drkzqAD");
        assertThat(rendered).contains("***REDACTED***").contains("tap-1");
    }

    @Test
    @DisplayName("surfaces the Graph error code so a missing directory role is diagnosable")
    void graphError_carriesErrorCode() {
        server.expect(requestTo(BASE + "/users/" + OID + "/authentication/methods"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("""
                              {"error":{"code":"Authentication_RequestFromUnsupportedUserRole",
                                        "message":"The signed-in user lacks the required role."}}
                              """)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.listAuthMethods(OID))
                .isInstanceOf(EntraDirectoryException.class)
                .satisfies(e -> {
                    EntraDirectoryException ex = (EntraDirectoryException) e;
                    assertThat(ex.getHttpStatus()).isEqualTo(403);
                    assertThat(ex.getGraphErrorCode())
                            .isEqualTo("Authentication_RequestFromUnsupportedUserRole");
                });
    }

    @Test
    @DisplayName("detects an external guest, who cannot be issued a Temporary Access Pass")
    void isExternalGuest_detectsExtMarker() {
        server.expect(requestTo(BASE + "/users/" + OID + "?$select=userType,userPrincipalName"))
                .andRespond(withSuccess("""
                        {"userType":"Guest",
                         "userPrincipalName":"jane_contoso.com#EXT#@operator.onmicrosoft.com"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.isExternalGuest(OID)).isTrue();
    }

    @Test
    @DisplayName("an internal member is not an external guest")
    void isExternalGuest_memberIsFalse() {
        server.expect(requestTo(BASE + "/users/" + OID + "?$select=userType,userPrincipalName"))
                .andRespond(withSuccess("""
                        {"userType":"Member","userPrincipalName":"jane@operator.onmicrosoft.com"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.isExternalGuest(OID)).isFalse();
    }

    @Test
    @DisplayName("reads authentication contexts, including whether each is published to apps")
    void listAuthenticationContexts_mapsAvailability() {
        server.expect(requestTo(BASE + "/identity/conditionalAccess/authenticationContextClassReferences"))
                .andRespond(withSuccess("""
                        {"value":[
                          {"id":"c1","displayName":"Registerwerk step-up","isAvailable":true},
                          {"id":"c2","displayName":"Draft","isAvailable":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var contexts = adapter.listAuthenticationContexts();

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).isAvailable()).isTrue();
        // An unpublished context can never be satisfied — it produces a sign-in redirect loop.
        assertThat(contexts.get(1).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("revokeSignInSessions posts to the documented action endpoint")
    void revokeSignInSessions_callsAction() {
        server.expect(requestTo(BASE + "/users/" + OID + "/revokeSignInSessions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"value\":true}", MediaType.APPLICATION_JSON));

        adapter.revokeSignInSessions(OID);

        server.verify();
    }

    private static String methodsPayload() {
        return """
               {"value":[
                 {"@odata.type":"#microsoft.graph.microsoftAuthenticatorAuthenticationMethod",
                  "id":"auth-1","displayName":"Pixel 9","isDefault":true,
                  "createdDateTime":"2026-03-14T09:00:00Z"},
                 {"@odata.type":"#microsoft.graph.phoneAuthenticationMethod",
                  "id":"phone-1","phoneNumber":"+49 170 *******89","isDefault":false},
                 {"@odata.type":"#microsoft.graph.passwordAuthenticationMethod","id":"pwd-1"}
               ]}
               """;
    }
}
