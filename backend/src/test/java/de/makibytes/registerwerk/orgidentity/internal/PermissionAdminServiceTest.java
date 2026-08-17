package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.internal.EcosystemOnchainBroadcaster;
import de.makibytes.registerwerk.orgidentity.internal.EcosystemTxGateway;
import de.makibytes.registerwerk.orgidentity.internal.PermissionAdminService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionAdminService — cross-tier revoke isolation + dual-control persistence")
class PermissionAdminServiceTest {

    @Mock private PermissionDefinitionRepository definitionRepository;
    @Mock private PermissionGrantRepository grantRepository;
    @Mock private OrgRegistrationRepository registrationRepository;
    @Mock private EcosystemTrustedIssuerRepository trustedIssuerRepository;
    @Mock private EcosystemTxGateway txGateway;
    @Mock private EcosystemOnchainBroadcaster broadcaster;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PermissionAdminService service;

    private PermissionGrant orgGrant(UUID id, UUID orgRegistrationId) {
        PermissionGrant grant = new PermissionGrant();
        grant.setId(id);
        grant.setPermissionDefinitionId(UUID.randomUUID());
        grant.setOrgRegistrationId(orgRegistrationId);
        grant.setGrantType(PermissionGrantType.ORG);
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        return grant;
    }

    @Test
    @DisplayName("revokeRoleGrant (company-side) rejects an ORG-type grant belonging to the caller's own org")
    void revokeRoleGrant_rejectsOrgTypeGrant() {
        UUID grantId = UUID.randomUUID();
        UUID orgRegistrationId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        PermissionGrant grant = orgGrant(grantId, orgRegistrationId); // ORG-type, not ROLE
        when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

        // Even if the grant genuinely belongs to the caller's own org, an ORG-type grant must
        // never be revocable through the company-facing (no-step-up) endpoint — only the
        // operator path (PermissionAdminController.revokeGrant) may revoke it.
        assertThatThrownBy(() -> service.revokeRoleGrant(grantId, entityId, UUID.randomUUID(), "COMPANY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(grantRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("revokeRoleGrant succeeds for a genuine ROLE-type grant belonging to the caller's org")
    void revokeRoleGrant_succeedsForRoleTypeGrant() {
        UUID grantId = UUID.randomUUID();
        UUID orgRegistrationId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        PermissionGrant grant = orgGrant(grantId, orgRegistrationId);
        grant.setGrantType(PermissionGrantType.ROLE);
        grant.setRoleCode("TRADER");
        when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(grant.getPermissionDefinitionId());
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(grant.getPermissionDefinitionId())).thenReturn(Optional.of(definition));

        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setLegalEntityId(entityId);
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        when(registrationRepository.findById(orgRegistrationId)).thenReturn(Optional.of(org));
        when(grantRepository.save(any(PermissionGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        PermissionGrant result = service.revokeRoleGrant(grantId, entityId, UUID.randomUUID(), "COMPANY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(PermissionGrantStatus.REVOKED);
    }

    @Test
    @DisplayName("revokeRoleGrant rejects a ROLE-type grant belonging to a different org (no cross-tenant leak)")
    void revokeRoleGrant_rejectsDifferentOrg() {
        UUID grantId = UUID.randomUUID();
        UUID orgRegistrationId = UUID.randomUUID();
        UUID ownEntityId = UUID.randomUUID();
        UUID otherEntityId = UUID.randomUUID();

        PermissionGrant grant = orgGrant(grantId, orgRegistrationId);
        grant.setGrantType(PermissionGrantType.ROLE);
        when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(grant.getPermissionDefinitionId());
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(grant.getPermissionDefinitionId())).thenReturn(Optional.of(definition));

        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setLegalEntityId(otherEntityId); // belongs to someone else
        when(registrationRepository.findById(orgRegistrationId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> service.revokeRoleGrant(grantId, ownEntityId, UUID.randomUUID(), "COMPANY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("revokeGrant (operator path) persists the dual-control approver id when provided")
    void revokeGrant_persistsDualControlApprover() {
        UUID grantId = UUID.randomUUID();
        UUID orgRegistrationId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        PermissionGrant grant = orgGrant(grantId, orgRegistrationId);
        when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(grant.getPermissionDefinitionId());
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(grant.getPermissionDefinitionId())).thenReturn(Optional.of(definition));

        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setLegalEntityId(UUID.randomUUID());
        when(registrationRepository.findById(orgRegistrationId)).thenReturn(Optional.of(org));
        when(grantRepository.save(any(PermissionGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        PermissionGrant result = service.revokeGrant(grantId, null, UUID.randomUUID(), "REGISTRY_ADMIN", approverId);

        assertThat(result.getDualControlApproverId()).isEqualTo(approverId);
        assertThat(result.getDualControlApprovedAt()).isNotNull();

        ArgumentCaptor<de.makibytes.registerwerk.orgidentity.events.PermissionRevokedEvent> captor =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.orgidentity.events.PermissionRevokedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
    }

    @Test
    @DisplayName("setRoleRestricted publishes an audit event")
    void setRoleRestricted_publishesEvent() {
        UUID orgRegistrationId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();

        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(definitionId);
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));

        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setLegalEntityId(entityId);
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        when(registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainConfigId))
                .thenReturn(Optional.of(org));

        PermissionGrant grant = orgGrant(UUID.randomUUID(), orgRegistrationId);
        when(grantRepository.findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatus(
                definitionId, orgRegistrationId, PermissionGrantType.ORG, PermissionGrantStatus.ACTIVE))
                .thenReturn(Optional.of(grant));
        when(grantRepository.save(any(PermissionGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setRoleRestricted(entityId, chainConfigId, definitionId, true, UUID.randomUUID(), "COMPANY_ADMIN");

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue().getClass().getSimpleName()).isEqualTo("PermissionRoleRestrictionChangedEvent");
    }
}
