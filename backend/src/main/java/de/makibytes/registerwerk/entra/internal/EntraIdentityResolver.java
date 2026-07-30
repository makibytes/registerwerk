package de.makibytes.registerwerk.entra.internal;

import java.util.Optional;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityGate;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Classifies an account as local, a member or guest of the operator's tenant, or federated to a
 * customer's own tenant.
 *
 * <p>Precedence, most to least authoritative:
 * <ol>
 *   <li>Local auth mode, or a LOCAL account → {@code LOCAL}. Entra is not involved.</li>
 *   <li>The token's {@code tid} differs from the operator's tenant → {@code FEDERATED}. This is
 *       observed fact and beats any configuration.</li>
 *   <li>The legal entity is configured as federated → {@code FEDERATED}. Operator intent, used
 *       only before the user has ever signed in and recorded a {@code tid}.</li>
 *   <li>Otherwise a member or guest, distinguished by Graph when it is available.</li>
 * </ol>
 */
@Component
class EntraIdentityResolver implements EntraIdentityGate {

    private static final Logger log = LoggerFactory.getLogger(EntraIdentityResolver.class);

    private final RegisterwerkAuthProperties authProperties;
    private final RegisterwerkEntraProperties entraProperties;
    private final LegalEntityRepository legalEntityRepository;
    private final EntraDirectoryPort directory;

    EntraIdentityResolver(
            RegisterwerkAuthProperties authProperties,
            RegisterwerkEntraProperties entraProperties,
            LegalEntityRepository legalEntityRepository,
            EntraDirectoryPort directory) {
        this.authProperties = authProperties;
        this.entraProperties = entraProperties;
        this.legalEntityRepository = legalEntityRepository;
        this.directory = directory;
    }

    @Override
    public EntraIdentityModel classify(AppUser user) {
        if (!authProperties.isEntraEnabled() || user.getAuthProvider() != UserAuthProvider.ENTRA) {
            return EntraIdentityModel.LOCAL;
        }

        UUID operatorTenant = operatorTenantId();
        UUID homeTenant = user.getEntraTenantId();
        if (operatorTenant != null && homeTenant != null && !operatorTenant.equals(homeTenant)) {
            return EntraIdentityModel.FEDERATED;
        }

        if (homeTenant == null && configuredModelOf(user) == EntraIdentityModel.FEDERATED) {
            return EntraIdentityModel.FEDERATED;
        }

        if (!directory.isEnabled() || user.getEntraObjectId() == null) {
            // Without Graph we cannot tell member from guest. WORKFORCE_MEMBER is the safe
            // assumption: both are manageable, and the narrower guest-only restriction (no TAP)
            // is separately re-checked by supportsTemporaryAccessPass before it matters.
            return EntraIdentityModel.WORKFORCE_MEMBER;
        }

        return classifyViaGraph(user);
    }

    @Override
    public boolean supportsTemporaryAccessPass(AppUser user) {
        EntraIdentityModel model = classify(user);
        if (!model.isManagedHere() || user.getEntraObjectId() == null || !directory.isEnabled()) {
            return false;
        }
        if (model == EntraIdentityModel.WORKFORCE_MEMBER) {
            return true;
        }
        // Guest: only an *internal* guest can hold a TAP.
        try {
            return !directory.isExternalGuest(user.getEntraObjectId().toString());
        } catch (RuntimeException e) {
            // Fail closed. Offering an action Entra will reject wastes the operator's time during
            // an incident and reads as a platform fault.
            log.warn("Could not determine guest type for user {}: {}", user.getId(), e.getMessage());
            return false;
        }
    }

    private EntraIdentityModel classifyViaGraph(AppUser user) {
        try {
            return directory.classifyPrincipal(user.getEntraObjectId().toString());
        } catch (RuntimeException e) {
            log.warn("Could not classify user {} via Graph: {}", user.getId(), e.getMessage());
            return EntraIdentityModel.WORKFORCE_MEMBER;
        }
    }

    /**
     * The identity model the operator configured for this customer, before any of their users has
     * signed in. Stored as a String on {@code LegalEntity} to keep {@code customer} free of a
     * dependency on this module; parsed leniently, since an unrecognised value should degrade to
     * the manageable default rather than break sign-in.
     */
    private EntraIdentityModel configuredModelOf(AppUser user) {
        if (user.getLegalEntityId() == null) {
            return EntraIdentityModel.WORKFORCE_GUEST;
        }
        return legalEntityRepository.findById(user.getLegalEntityId())
                .map(LegalEntity::getIdentityModel)
                .flatMap(EntraIdentityResolver::parseModel)
                .orElse(EntraIdentityModel.WORKFORCE_GUEST);
    }

    private static Optional<EntraIdentityModel> parseModel(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EntraIdentityModel.valueOf(value.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised legal_entity.identity_model value '{}' — treating as WORKFORCE_GUEST", value);
            return Optional.empty();
        }
    }

    private UUID operatorTenantId() {
        String configured = entraProperties.getTenantId();
        if (configured.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(configured);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
