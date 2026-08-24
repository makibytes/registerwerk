package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
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
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgChainReconciliationService}, including the in-memory
 * {@code registerwerk_org_chain_drift_open_total} snapshot gauge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrgChainReconciliationService — detection-only drift checks")
class OrgChainReconciliationServiceTest {

    @Mock private OrgRegistrationRepository registrationRepository;
    @Mock private OrgMemberWalletRepository walletRepository;
    @Mock private EcosystemTxGateway txGateway;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SimpleMeterRegistry meterRegistry;
    private OrgChainReconciliationService service;

    private static final String ORG_ADDRESS = "0x1111111111111111111111111111111111111111";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new OrgChainReconciliationService(
                registrationRepository, walletRepository, txGateway, eventPublisher, meterRegistry);
    }

    private double driftGauge() {
        return meterRegistry.get("registerwerk_org_chain_drift_open_total").gauge().value();
    }

    private OrgRegistration activeOrg() {
        OrgRegistration org = new OrgRegistration();
        org.setId(UUID.randomUUID());
        org.setOrgAddress(ORG_ADDRESS);
        org.setChainConfigId(UUID.randomUUID());
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        return org;
    }

    private OrgMemberWallet activeWallet(UUID orgRegistrationId) {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setId(UUID.randomUUID());
        wallet.setOrgRegistrationId(orgRegistrationId);
        wallet.setWalletAddress("0x2222222222222222222222222222222222222222");
        wallet.setStatus(MemberWalletStatus.ACTIVE);
        return wallet;
    }

    /** Dispatches by read-function name, same pattern as PermissionChainReconciliationServiceTest.
     *  Uses doAnswer/when (not when/thenAnswer) since some tests re-stub mid-test. */
    private void stubOrgRegistryReads(Boolean isOrgActive, String orgOfResult) {
        doAnswer(invocation -> {
            Function fn = invocation.getArgument(1, Function.class);
            return switch (fn.getName()) {
                case "isOrgActive" -> isOrgActive == null ? List.<Type>of() : List.<Type>of(new Bool(isOrgActive));
                case "orgOf" -> orgOfResult == null ? List.<Type>of() : List.<Type>of(new Address(orgOfResult));
                default -> List.<Type>of();
            };
        }).when(txGateway).callOrgRegistry(any(), any());
    }

    @Test
    @DisplayName("no drift event when org and its wallet are both in sync onchain")
    void reconcile_noDriftWhenInSync() {
        OrgRegistration org = activeOrg();
        OrgMemberWallet wallet = activeWallet(org.getId());
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(walletRepository.findByOrgRegistrationIdOrderByCreatedAtDesc(org.getId())).thenReturn(List.of(wallet));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(new ChainConfig());
        stubOrgRegistryReads(true, ORG_ADDRESS);

        service.reconcile();

        verify(eventPublisher, never()).publishEvent(any());
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("publishes drift event when org is ACTIVE in DB but not active onchain")
    void reconcile_detectsOrgDrift() {
        OrgRegistration org = activeOrg();
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(new ChainConfig());
        stubOrgRegistryReads(false, null);

        service.reconcile();

        ArgumentCaptor<OrgChainDriftEvent> captor = ArgumentCaptor.forClass(OrgChainDriftEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().subjectId()).isEqualTo(org.getId());
        assertThat(captor.getValue().details()).containsEntry("onchainActive", false);
        assertThat(driftGauge()).isEqualTo(1.0);
        // org-inactive drift returns early — member wallets aren't even checked in that cycle
        verify(walletRepository, never()).findByOrgRegistrationIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("publishes drift event when a member wallet is bound to a different org onchain")
    void reconcile_detectsWalletDrift() {
        OrgRegistration org = activeOrg();
        OrgMemberWallet wallet = activeWallet(org.getId());
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(walletRepository.findByOrgRegistrationIdOrderByCreatedAtDesc(org.getId())).thenReturn(List.of(wallet));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(new ChainConfig());
        // org itself checks out, but the wallet is bound to a DIFFERENT org onchain
        stubOrgRegistryReads(true, "0x9999999999999999999999999999999999999999");

        service.reconcile();

        ArgumentCaptor<OrgChainDriftEvent> captor = ArgumentCaptor.forClass(OrgChainDriftEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().subjectId()).isEqualTo(wallet.getId());
        assertThat(driftGauge()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("skips a non-ACTIVE member wallet without checking it onchain")
    void reconcile_skipsInactiveWallet() {
        OrgRegistration org = activeOrg();
        OrgMemberWallet inactiveWallet = activeWallet(org.getId());
        inactiveWallet.setStatus(MemberWalletStatus.REMOVED);
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(walletRepository.findByOrgRegistrationIdOrderByCreatedAtDesc(org.getId())).thenReturn(List.of(inactiveWallet));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(new ChainConfig());
        stubOrgRegistryReads(true, null);

        service.reconcile();

        verify(eventPublisher, never()).publishEvent(any());
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("a read failure for one org is swallowed and does not abort the sweep")
    void reconcile_readFailureSwallowed() {
        OrgRegistration org = activeOrg();
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(txGateway.requireChain(org.getChainConfigId())).thenThrow(new IllegalStateException("contract not configured"));

        service.reconcile(); // must not throw

        verify(eventPublisher, never()).publishEvent(any());
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("drift gauge resets to 0 on a subsequent clean sweep")
    void reconcile_gaugeResetsOnCleanSweep() {
        OrgRegistration org = activeOrg();
        when(registrationRepository.findByStatus(OrgRegistrationStatus.ACTIVE)).thenReturn(List.of(org));
        when(txGateway.requireChain(org.getChainConfigId())).thenReturn(new ChainConfig());

        stubOrgRegistryReads(false, null);
        service.reconcile();
        assertThat(driftGauge()).isEqualTo(1.0);

        stubOrgRegistryReads(true, null);
        service.reconcile();
        assertThat(driftGauge()).isZero();
    }
}
