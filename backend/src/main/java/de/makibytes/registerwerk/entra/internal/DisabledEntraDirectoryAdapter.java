package de.makibytes.registerwerk.entra.internal;

import java.util.List;

import de.makibytes.registerwerk.entra.api.AuthContextRef;
import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraNotConfiguredException;
import de.makibytes.registerwerk.entra.api.EntraUserMfaStatus;
import de.makibytes.registerwerk.entra.api.TemporaryAccessPass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link EntraDirectoryPort} used whenever no real Graph adapter is present — the normal
 * state in local and demo mode, and in every test that does not deliberately stub Graph.
 *
 * <p>Reads succeed with "not applicable"; mutations throw. See {@link EntraDirectoryPort} for
 * why that asymmetry is deliberate.
 */
@Configuration
class DisabledEntraDirectoryAdapter {

    private static final String NOT_CONFIGURED =
            "Microsoft Entra ID integration is not configured (registerwerk.entra.support-enabled=false).";

    private static final String NOT_APPLICABLE =
            "Two-factor authentication is managed by Microsoft Entra ID and is not active in this environment.";

    @Bean
    @ConditionalOnMissingBean(EntraDirectoryPort.class)
    EntraDirectoryPort disabledEntraDirectoryPort() {
        return new EntraDirectoryPort() {

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public EntraUserMfaStatus getMfaStatus(String entraObjectId) {
                return EntraUserMfaStatus.notApplicable(NOT_APPLICABLE);
            }

            @Override
            public List<EntraAuthMethod> listAuthMethods(String entraObjectId) {
                return List.of();
            }

            @Override
            public void deleteAuthMethod(String entraObjectId, EntraAuthMethodType type, String methodId) {
                throw new EntraNotConfiguredException(NOT_CONFIGURED);
            }

            @Override
            public ResetOutcome resetAllAuthMethods(String entraObjectId) {
                throw new EntraNotConfiguredException(NOT_CONFIGURED);
            }

            @Override
            public void revokeSignInSessions(String entraObjectId) {
                throw new EntraNotConfiguredException(NOT_CONFIGURED);
            }

            @Override
            public TemporaryAccessPass issueTemporaryAccessPass(
                    String entraObjectId, int lifetimeMinutes, boolean usableOnce) {
                throw new EntraNotConfiguredException(NOT_CONFIGURED);
            }

            @Override
            public List<AuthContextRef> listAuthenticationContexts() {
                return List.of();
            }

            @Override
            public EntraIdentityModel classifyPrincipal(String entraObjectId) {
                return EntraIdentityModel.LOCAL;
            }

            @Override
            public boolean isExternalGuest(String entraObjectId) {
                // Nothing can be confirmed without Graph, and this gates a credential-issuing
                // action, so answer the way that withholds it.
                return true;
            }
        };
    }
}
