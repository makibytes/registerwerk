package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import de.makibytes.registerwerk.orgidentity.internal.EcosystemOnchainBroadcaster;
import de.makibytes.registerwerk.orgidentity.internal.EcosystemTxGateway;
import de.makibytes.registerwerk.orgidentity.internal.PermissionAdminService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

        verify(grantRepository, never()).save(any());
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

        assertThat(result.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
    }

    @Test
    @DisplayName("failed revocation can be explicitly retried without reopening access")
    void revokeGrant_failedRevocationCanRetryFailClosed() {
        UUID grantId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(grantId, registrationId);
        grant.setStatus(PermissionGrantStatus.REVOCATION_FAILED);
        grant.setRevokedTx("0xfailed");
        when(grantRepository.findById(grantId)).thenReturn(Optional.of(grant));

        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(grant.getPermissionDefinitionId());
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(grant.getPermissionDefinitionId())).thenReturn(Optional.of(definition));
        OrgRegistration org = new OrgRegistration();
        org.setId(registrationId);
        org.setLegalEntityId(UUID.randomUUID());
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(org));
        when(grantRepository.save(any(PermissionGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        PermissionGrant result = service.revokeGrant(grantId, null, UUID.randomUUID(), "REGISTRY_ADMIN", null);

        assertThat(result.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
        assertThat(result.getRevokedTx()).isNull();
    }

    @Test
    @DisplayName("grantToOrg rejects a second grant while a revocation is unresolved onchain")
    void grantToOrg_rejectsUnresolvedRevocationDuplicate() {
        UUID definitionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        PermissionDefinition definition = new PermissionDefinition();
        definition.setId(definitionId);
        definition.setCode("bond-desk.subscribe");
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        OrgRegistration org = new OrgRegistration();
        org.setId(registrationId);
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(org));
        when(grantRepository.findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatusIn(
                definitionId, registrationId, PermissionGrantType.ORG,
                List.of(PermissionGrantStatus.PENDING, PermissionGrantStatus.ACTIVE,
                        PermissionGrantStatus.REVOCATION_PENDING, PermissionGrantStatus.REVOCATION_FAILED)))
                .thenReturn(Optional.of(orgGrant(UUID.randomUUID(), registrationId)));

        assertThatThrownBy(() -> service.grantToOrg(definitionId, registrationId,
                UUID.randomUUID(), "REGISTRY_ADMIN", null))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(grantRepository, never()).save(any());
    }

    @Test
    @DisplayName("trusted issuer removal is fail-closed while receipt finality is pending")
    void removeTrustedIssuer_entersRemovalPending() {
        UUID issuerId = UUID.randomUUID();
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setId(issuerId);
        issuer.setIssuerAddress("0xissuer");
        issuer.setStatus(TrustedIssuerStatus.ACTIVE);
        when(trustedIssuerRepository.findById(issuerId)).thenReturn(Optional.of(issuer));
        when(trustedIssuerRepository.save(any(EcosystemTrustedIssuer.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EcosystemTrustedIssuer result = service.removeTrustedIssuer(
                issuerId, UUID.randomUUID(), "REGISTRY_ADMIN", null);

        assertThat(result.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVAL_PENDING);
        assertThat(result.getRemovedAt()).isNotNull();
    }

    @Test
    @DisplayName("addTrustedIssuer rejects an unresolved predecessor lifecycle")
    void addTrustedIssuer_rejectsUnresolvedPredecessor() {
        UUID chainConfigId = UUID.randomUUID();
        EcosystemTrustedIssuer predecessor = new EcosystemTrustedIssuer();
        predecessor.setStatus(TrustedIssuerStatus.REMOVAL_PENDING);
        predecessor.setIssuerAddress("0xissuer");
        when(trustedIssuerRepository.findLiveIssuer(chainConfigId, "0xISSUER"))
                .thenReturn(Optional.of(predecessor));

        assertThatThrownBy(() -> service.addTrustedIssuer(chainConfigId, "0xISSUER", List.of(1L),
                null, UUID.randomUUID(), "REGISTRY_ADMIN", null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("unresolved lifecycle");

        verify(trustedIssuerRepository, never()).save(any());
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

        PermissionGrant result = service.setRoleRestricted(
                entityId, chainConfigId, definitionId, true, UUID.randomUUID(), "COMPANY_ADMIN");

        assertThat(result.getRoleRestrictionStatus()).isEqualTo(RoleRestrictionStatus.CHANGE_PENDING);
        assertThat(result.getRequestedRoleRestricted()).isTrue();
        assertThat(result.isConfirmedRoleRestricted()).isFalse();
        assertThat(result.isRoleRestricted()).isTrue(); // conservative effective authorization value

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue().getClass().getSimpleName()).isEqualTo("PermissionRoleRestrictionChangedEvent");
    }
}
