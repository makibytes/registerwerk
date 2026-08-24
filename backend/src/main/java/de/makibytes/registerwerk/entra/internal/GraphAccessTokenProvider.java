package de.makibytes.registerwerk.entra.internal;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredentialBuilder;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * App-only access tokens for Microsoft Graph.
 *
 * <p>Uses the {@code azure-identity} client already on the classpath for the Key Vault KEK
 * provider. {@code ClientSecretCredential} caches tokens and refreshes them ahead of expiry, so
 * there is no reason to add caching here.
 *
 * <p>App-only rather than on-behalf-of because the operator support console must work for a
 * customer who cannot sign in — which is the entire point of the lost-phone flow. That makes the
 * service principal's directory role the security boundary: it needs Authentication
 * Administrator, and deliberately not Privileged Authentication Administrator, so it cannot
 * touch admin accounts.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.entra.support-enabled", havingValue = "true")
class GraphAccessTokenProvider {

    private static final TokenRequestContext GRAPH_SCOPE =
            new TokenRequestContext().addScopes("https://graph.microsoft.com/.default");

    private final TokenCredential credential;

    GraphAccessTokenProvider(RegisterwerkEntraProperties props) {
        this.credential = new ClientSecretCredentialBuilder()
                .tenantId(props.getTenantId())
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .build();
    }

    String bearerToken() {
        return credential.getTokenSync(GRAPH_SCOPE).getToken();
    }
}
