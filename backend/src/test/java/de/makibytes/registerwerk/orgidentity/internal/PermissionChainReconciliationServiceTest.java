package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.events.OrgChainDriftEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionChainReconciliationService — detection-only drift checks")
class PermissionChainReconciliationServiceTest {

    @Mock private PermissionGrantRepository grantRepository;
    @Mock private PermissionDefinitionRepository definitionRepository;
    @Mock private OrgRegistrationRepository registrationRepository;
    @Mock private EcosystemTxGateway txGateway;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SimpleMeterRegistry meterRegistry;
    private PermissionChainReconciliationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PermissionChainReconciliationService(
                grantRepository, definitionRepository, registrationRepository, txGateway, eventPublisher, meterRegistry);
    }

    private double driftGauge() {
        return meterRegistry.get("registerwerk_permission_chain_drift_open_total").gauge().value();
    }

    private OrgRegistration org(UUID orgRegistrationId) {
        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setOrgAddress("0x1111111111111111111111111111111111111111");
        org.setChainConfigId(UUID.randomUUID());
        return org;
    }

    private PermissionDefinition definition(UUID definitionId) {
        PermissionDefinition d = new PermissionDefinition();
        d.setId(definitionId);
        d.setCode("bond-desk.subscribe");
        return d;
    }

    private PermissionGrant orgGrant(UUID orgRegistrationId, UUID definitionId, boolean roleRestricted) {
        PermissionGrant grant = new PermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setOrgRegistrationId(orgRegistrationId);
        grant.setPermissionDefinitionId(definitionId);
        grant.setGrantType(PermissionGrantType.ORG);
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        grant.setRoleRestricted(roleRestricted);
        return grant;
    }

    private ChainConfig chainConfig() {
        return new ChainConfig();
    }

    /** Dispatches by read-function name so each test only needs to declare the onchain values it cares about.
     *  Uses doAnswer/when (not when/thenAnswer) since some tests re-stub this call mid-test — when() would
     *  re-invoke whatever answer is already active during the recording phase and see null args. */
    private void stubPermissionRegistryReads(Map<String, Boolean> resultsByFunctionName) {
        org.mockito.Mockito.doAnswer(invocation -> {
            Function fn = invocation.getArgument(1, Function.class);
            Boolean result = resultsByFunctionName.get(fn.getName());
            return result == null ? List.<Type>of() : List.<Type>of(new Bool(result));
        }).when(txGateway).callPermissionRegistry(any(), any());
    }

    @Test
    @DisplayName("no drift event when DB and chain agree (ORG grant, matching restriction flag)")
    void reconcile_noDriftWhenInSync() {
        UUID orgRegId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(orgRegId, definitionId, false);
        OrgRegistration org = org(orgRegId);
        PermissionDefinition definition = definition(definitionId);

        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(orgRegId)).thenReturn(Optional.of(org));
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(chainConfig());
        stubPermissionRegistryReads(Map.of("orgGranted", true, "isRoleRestricted", false));

        service.reconcile();

        verify(eventPublisher, never()).publishEvent(any());
        verify(grantRepository, never()).save(any());
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("publishes drift event when an ORG grant is ACTIVE in DB but not granted onchain")
    void reconcile_detectsOrgGrantDrift() {
        UUID orgRegId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(orgRegId, definitionId, false);
        OrgRegistration org = org(orgRegId);
        PermissionDefinition definition = definition(definitionId);

        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(orgRegId)).thenReturn(Optional.of(org));
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(chainConfig());
        // drift: DB says ACTIVE, chain says not granted
        stubPermissionRegistryReads(Map.of("orgGranted", false, "isRoleRestricted", false));

        service.reconcile();

        ArgumentCaptor<OrgChainDriftEvent> captor = ArgumentCaptor.forClass(OrgChainDriftEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().subjectId()).isEqualTo(grant.getId());
        assertThat(captor.getValue().details()).containsEntry("onchainGranted", false);
        verify(grantRepository, never()).save(any()); // detection only, never auto-corrects
        assertThat(driftGauge()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("drift gauge resets to 0 on a subsequent clean sweep (alerting metrics)")
    void reconcile_gaugeResetsOnCleanSweep() {
        UUID orgRegId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(orgRegId, definitionId, false);
        OrgRegistration org = org(orgRegId);
        PermissionDefinition definition = definition(definitionId);

        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(orgRegId)).thenReturn(Optional.of(org));
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(chainConfig());

        stubPermissionRegistryReads(Map.of("orgGranted", false, "isRoleRestricted", false));
        service.reconcile();
        assertThat(driftGauge()).isEqualTo(1.0);

        stubPermissionRegistryReads(Map.of("orgGranted", true, "isRoleRestricted", false));
        service.reconcile();
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("publishes drift event when the role-restriction flag disagrees (most dangerous drift: broadened onchain)")
    void reconcile_detectsRoleRestrictionDrift() {
        UUID orgRegId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(orgRegId, definitionId, true); // DB says restricted
        OrgRegistration org = org(orgRegId);
        PermissionDefinition definition = definition(definitionId);

        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(orgRegId)).thenReturn(Optional.of(org));
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(chainConfig());
        // drift: org admin flipped it unrestricted onchain
        stubPermissionRegistryReads(Map.of("orgGranted", true, "isRoleRestricted", false));

        service.reconcile();

        ArgumentCaptor<OrgChainDriftEvent> captor = ArgumentCaptor.forClass(OrgChainDriftEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().details())
                .containsEntry("dbRoleRestricted", true)
                .containsEntry("onchainRoleRestricted", false);
    }

    @Test
    @DisplayName("publishes drift event when a ROLE-tier delegation is ACTIVE in DB but not granted onchain")
    void reconcile_detectsRoleGrantDrift() {
        UUID orgRegId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = new PermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setOrgRegistrationId(orgRegId);
        grant.setPermissionDefinitionId(definitionId);
        grant.setGrantType(PermissionGrantType.ROLE);
        grant.setRoleCode("TRADER");
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        OrgRegistration org = org(orgRegId);
        PermissionDefinition definition = definition(definitionId);

        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(orgRegId)).thenReturn(Optional.of(org));
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(chainConfig());
        stubPermissionRegistryReads(Map.of("roleGranted", false));

        service.reconcile();

        ArgumentCaptor<OrgChainDriftEvent> captor = ArgumentCaptor.forClass(OrgChainDriftEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().details())
                .containsEntry("grantType", "ROLE")
                .containsEntry("roleCode", "TRADER");
    }

    @Test
    @DisplayName("skips a grant whose org registration no longer resolves, without throwing")
    void reconcile_skipsOrphanedGrantGracefully() {
        UUID definitionId = UUID.randomUUID();
        PermissionGrant grant = orgGrant(UUID.randomUUID(), definitionId, false);
        when(grantRepository.findByStatus(PermissionGrantStatus.ACTIVE)).thenReturn(List.of(grant));
        when(registrationRepository.findById(grant.getOrgRegistrationId())).thenReturn(Optional.empty());

        service.reconcile();

        verify(eventPublisher, never()).publishEvent(any());
    }
}
